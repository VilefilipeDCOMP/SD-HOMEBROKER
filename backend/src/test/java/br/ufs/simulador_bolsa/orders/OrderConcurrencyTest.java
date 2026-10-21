package br.ufs.simulador_bolsa.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OrderConcurrencyTest {

    private static final int THREADS = 50;

    private OrdersKit kit;

    @AfterEach
    void tearDown() {
        if (kit != null) {
            kit.close();
        }
    }

    private List<OrderResult> submitAtOnce(IntFunction<OrderRequest> requestFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OrderResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return kit.service.submit(requestFor.apply(n));
                }));
            }
            ready.await();
            go.countDown();

            List<OrderResult> results = new ArrayList<>();
            for (Future<OrderResult> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fiftyThreadsCompeteForBalanceThatCoversOnlyTen() throws Exception {
        kit = new OrdersKit("1000.00");

        List<OrderResult> results = submitAtOnce(n -> OrdersKit.buy("u1", "c" + n, "PETR4", 1, "100.00"));

        int accepted = 0;
        int rejected = 0;
        for (OrderResult result : results) {
            Order order = kit.awaitFinal(result.orderId());
            if (order.status() == OrderStatus.ACCEPTED) {
                accepted++;
            } else {
                rejected++;
                assertEquals(RejectReason.INSUFFICIENT_FUNDS, order.rejectReason());
            }
        }

        assertEquals(10, accepted);
        assertEquals(40, rejected);
        assertEquals(0, BigDecimal.ZERO.compareTo(kit.wallet.getAvailableBalance("u1")));
        assertEquals(0, new BigDecimal("1000.00").compareTo(kit.wallet.getReservedBalance("u1")));
    }

    @Test
    void fiftyThreadsCompeteForSharesThatCoverOnlyTen() throws Exception {
        kit = new OrdersKit("0.00");
        kit.wallet.depositShares("u1", "VALE3", 100);

        List<OrderResult> results = submitAtOnce(n -> OrdersKit.sell("u1", "c" + n, "VALE3", 10, "68.00"));

        int accepted = 0;
        for (OrderResult result : results) {
            if (kit.awaitFinal(result.orderId()).status() == OrderStatus.ACCEPTED) {
                accepted++;
            }
        }

        assertEquals(10, accepted);
        assertEquals(0L, kit.wallet.getAvailableShares("u1", "VALE3"));
    }

    @Test
    void sameClientOrderIdSentByManyThreadsCreatesOneOrder() throws Exception {
        kit = new OrdersKit("1000.00");

        List<OrderResult> results = submitAtOnce(n -> OrdersKit.buy("u1", "mesmo-id", "PETR4", 1, "100.00"));

        Set<String> orderIds = new HashSet<>();
        for (OrderResult result : results) {
            orderIds.add(result.orderId());
        }
        kit.awaitFinal(orderIds.iterator().next());

        assertEquals(1, orderIds.size());
        assertEquals(1, kit.service.findByUser("u1").size());
        assertEquals(0, new BigDecimal("100.00").compareTo(kit.wallet.getReservedBalance("u1")));
    }

    @Test
    void sequenceNumbersAreUniqueUnderConcurrency() throws Exception {
        kit = new OrdersKit("1000000.00");

        submitAtOnce(n -> OrdersKit.buy("u1", "c" + n, "PETR4", 1, "1.00"));

        Set<Long> sequences = new HashSet<>();
        for (Order order : kit.service.findByUser("u1")) {
            sequences.add(order.sequenceNumber());
        }
        assertEquals(THREADS, sequences.size());
    }
}
