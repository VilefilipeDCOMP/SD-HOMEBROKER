package br.ufs.simulador_bolsa.orders;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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
    private final BlockingQueue<Order> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

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
        try {
            consumer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();//mata a thread atual
        }
        consumer = null;
        //e o que foi perdido com esse shutdown, fica registrado no log
        int pending = queue.size();
        if (pending > 0) {
            log.warn("ORDER_QUEUE_DISCARDED pendentes={}", pending);
        }
    }

    public boolean enqueue(Order order) {
        return queue.offer(order);
    }

    //contagem de quantas ações estao na fila
    public int pendingCount() {
        return queue.size();
    }

    //é o "do", fica rodando ai até dar exception de interrupcao
    private void runLoop() {
        while (running) {
            try {
                process(queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.error("ORDER_PROCESSOR_ERROR", e);
            }
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
            order.accept();
            if (persistenceService != null) {
                try {
                    persistenceService.persistOpenOrder(order);
                } catch (RuntimeException e) {
                    log.warn("ORDER_DB_PERSIST_WARN orderId={} reason={}", order.orderId(), e.getMessage());
                }
            }
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
