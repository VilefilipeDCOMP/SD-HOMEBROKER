package br.ufs.simulador_bolsa.orders;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.ufs.simulador_bolsa.observability.MarketMetricsService;
import br.ufs.simulador_bolsa.wallet.WalletPort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

//processa as ações, obviamente, processa uma por vez com ordem de chegada, com uma thread só
//único problema vai ser se tiver uma queda no servidor, pq a fila de processamento fica na memória
//entao se cair, perde a fila atual
@RequiredArgsConstructor
@Component
public class OrderProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessor.class);

    //tamanho max da fila
    static final int QUEUE_CAPACITY = 10_000;

    private final WalletPort wallet;
    private final OrderNotifier notifier;
    private final MatchingEngine engine;
    private final BlockingQueue<OrderCommand> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    @Autowired(required = false)
    private OrderPersistenceService persistenceService;

    @Autowired(required = false)
    private MarketMetricsService metricsService;

    private volatile boolean running;
    private Thread consumer;

    //o spring chama esse metodo quando starta a aplicação
    @PostConstruct
    public synchronized void start() {
        if (consumer != null) return;
        running = true;
        consumer = new Thread(this::runLoop, "order-processor");
        consumer.setDaemon(true);
        consumer.start();
    }

    //spring chama essa outra quando a aplicação fecha
    @PreDestroy
    public synchronized void shutdown() {
        if (consumer == null) return;

        running = false;
        consumer.interrupt();

        int pending = queue.size();

        OrderCommand command;

        while ((command = queue.poll()) != null) {
            switch (command) {
                case OrderCommand.Cancel cancel ->
                        cancel.result().completeExceptionally(new RejectedExecutionException("Processador encerrado")
                        );

                case OrderCommand.Submit submit ->
                        submit.order().reject(RejectReason.INTERNAL_ERROR);
            }
        }

        try {
            consumer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        consumer = null;

        if (pending > 0) {
            log.warn("ORDER_QUEUE_DISCARDED pendentes={}", pending);
        }
    }

    public synchronized boolean enqueue(Order order) {
        return running && queue.offer(new OrderCommand.Submit(order));
    }

    public synchronized CompletableFuture<Boolean> enqueueCancel(Order order) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();

        if (!running) {
            result.completeExceptionally(new RejectedExecutionException("Processador indisponível"));
            return result;
        }

        if (!queue.offer(new OrderCommand.Cancel(order, result))) {
            result.completeExceptionally(new RejectedExecutionException("Fila de ordens cheia"));
        }

        return result;
    }

    //contagem de quantas ações estao na fila
    public int pendingCount() {
        return queue.size();
    }

    //é o "do", fica rodando ai até dar exception de interrupcao
    private void runLoop() {
        while (running) {
            try {
                OrderCommand command = queue.take();

                switch (command) {
                    case OrderCommand.Submit submit -> process(submit.order());
                    case OrderCommand.Cancel cancel -> processCancel(cancel);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.error("ORDER_PROCESSOR_ERROR", e);
            }
        }
    }

    private void processCancel(OrderCommand.Cancel command) {
        try {
            boolean cancelled = engine.cancelFromBook(command.order());

            command.result().complete(cancelled);

        } catch (RuntimeException e) {
            log.error("ORDER_CANCEL_FAILED orderId={}",
                    command.order().orderId(), e);

            command.result().completeExceptionally(e);
        }
    }

    //é o que vai decidir a ordem dos processos, vai notificar o user sempre
    void process(Order order) {
        try {
            decide(order);
        } catch (RuntimeException e) {
            log.error("ORDER_FAILED orderId={} userId={}", order.orderId(), order.userId(), e);
            order.reject(RejectReason.INTERNAL_ERROR);
            if (metricsService != null) {
                metricsService.recordOrderRejected(order.elapsedMillis());
            }
        }
        notifyQuietly(order);
    }

    //deixa "separado" o dinheiro na carteira
    private void decide(Order order) {
        boolean reserved;
        RejectReason failure;

        //se for compra, ele reserva o dinheiro, relação da quantidade e preço total
        if (order.side() == OrderSide.BUY) {
            reserved = wallet.reserveFunds(order.userId(), order.totalValue());
            failure = RejectReason.INSUFFICIENT_FUNDS;
        } else {//se não é compra, é venda
            //vai reservar as ações que vao ser vendidas
            reserved = wallet.reserveShares(order.userId(), order.symbol(), order.quantity());
            failure = RejectReason.INSUFFICIENT_SHARES;
        }

        double latencyMs = order.elapsedMillis();

        if (reserved) {
            if (persistenceService != null) {
                try {
                    Long databaseId = persistenceService.persistOpenOrder(order);

                    if (databaseId == null) {
                        throw new IllegalStateException("Não foi possível persistir a ordem");
                    }

                } catch (RuntimeException e) {
                    log.error("ORDER_DB_PERSIST_FAILED orderId={}", order.orderId(), e);

                    order.assignDatabaseId(null);

                    if (order.side() == OrderSide.BUY) {
                        wallet.releaseFunds(order.userId(), order.totalValue()
                        );
                    } else {
                        wallet.releaseShares(order.userId(), order.symbol(), order.quantity()
                        );
                    }

                    order.reject(RejectReason.INTERNAL_ERROR);

                    if (metricsService != null) {
                        metricsService.recordOrderRejected(order.elapsedMillis());
                    }
                    log.info("ORDER_REJECTED orderId={} reason={}", order.orderId(), RejectReason.INTERNAL_ERROR);

                    return;
                }
            }

            order.accept();
            if (metricsService != null) {
                metricsService.recordOrderAccepted(latencyMs);
            }
            log.info("ORDER_ACCEPTED orderId={} dbId={} userId={} seq={} {} {} x{} @{} latencyMs={}",
                    order.orderId(), order.databaseId(), order.userId(), order.sequenceNumber(),
                    order.side(), order.symbol(), order.quantity(), order.price(),
                    String.format(java.util.Locale.ROOT, "%.3f", latencyMs));
            engine.match(order);
        } else {
            order.reject(failure);
            if (metricsService != null) {
                metricsService.recordOrderRejected(latencyMs);
            }
            log.info("ORDER_REJECTED orderId={} userId={} seq={} reason={} latencyMs={}",
                    order.orderId(), order.userId(), order.sequenceNumber(), failure,
                    String.format(java.util.Locale.ROOT, "%.3f", latencyMs));
        }
    }

    //notifica, se der erro só deixa no log mesmo
    private void notifyQuietly(Order order) {
        try {
            notifier.orderUpdated(order);
            log.info("CLIENTS_NOTIFIED orderId={} userId={} status={}",
                    order.orderId(), order.userId(), order.status());
        } catch (RuntimeException e) {
            log.warn("ORDER_NOTIFY_FAILED orderId={} userId={}", order.orderId(), order.userId(), e);
        }
    }
}
