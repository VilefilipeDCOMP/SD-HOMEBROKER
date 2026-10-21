package br.ufs.simulador_bolsa.orders;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
    private final BlockingQueue<Order> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

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

        if (reserved) {
            order.accept();
            log.info("ORDER_ACCEPTED orderId={} userId={} seq={} {} {} x{} @{}",
                    order.orderId(), order.userId(), order.sequenceNumber(),
                    order.side(), order.symbol(), order.quantity(), order.price());
        } else {
            order.reject(failure);
            log.info("ORDER_REJECTED orderId={} userId={} seq={} reason={}",
                    order.orderId(), order.userId(), order.sequenceNumber(), failure);
        }
    }

    //notifica, se der erro só deixa no log mesmo
    private void notifyQuietly(Order order) {
        try {
            notifier.orderUpdated(order);
        } catch (RuntimeException e) {
            log.warn("ORDER_NOTIFY_FAILED orderId={} userId={}", order.orderId(), order.userId(), e);
        }
    }
}
