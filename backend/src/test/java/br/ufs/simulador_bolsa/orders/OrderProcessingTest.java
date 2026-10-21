package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.wallet.WalletPort;

import static org.junit.jupiter.api.Assertions.*;

class OrderProcessingTest {

    private OrdersKit kit;

    @AfterEach
    void tearDown() {
        if (kit != null) {
            kit.close();
        }
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "esperado " + expected + ", mas foi " + actual);
    }

    @Test
    void buyWithEnoughFundsIsAcceptedAndReservesMoney() throws Exception {
        kit = new OrdersKit("1000.00");

        OrderResult ack = kit.service.submit(OrdersKit.buy("u1", "c1", "PETR4", 5, "100.00"));
        assertEquals(OrderStatus.RECEIVED, ack.status());

        Order order = kit.awaitFinal(ack.orderId());
        assertEquals(OrderStatus.ACCEPTED, order.status());
        assertMoney("500.00", kit.wallet.getAvailableBalance("u1"));
        assertMoney("500.00", kit.wallet.getReservedBalance("u1"));
    }

    @Test
    void buyWithoutFundsIsRejected() throws Exception {
        kit = new OrdersKit("100.00");

        OrderResult ack = kit.service.submit(OrdersKit.buy("u1", "c1", "PETR4", 5, "100.00"));
        Order order = kit.awaitFinal(ack.orderId());

        assertEquals(OrderStatus.REJECTED, order.status());
        assertEquals(RejectReason.INSUFFICIENT_FUNDS, order.rejectReason());
        assertMoney("100.00", kit.wallet.getAvailableBalance("u1"));
    }

    @Test
    void sellWithoutSharesIsRejected() throws Exception {
        kit = new OrdersKit("1000.00");

        OrderResult ack = kit.service.submit(OrdersKit.sell("u1", "c1", "PETR4", 1, "35.00"));
        Order order = kit.awaitFinal(ack.orderId());

        assertEquals(OrderStatus.REJECTED, order.status());
        assertEquals(RejectReason.INSUFFICIENT_SHARES, order.rejectReason());
    }

    @Test
    void sellWithSharesIsAcceptedAndReservesShares() throws Exception {
        kit = new OrdersKit("1000.00");
        kit.wallet.depositShares("u1", "PETR4", 10);

        OrderResult ack = kit.service.submit(OrdersKit.sell("u1", "c1", "PETR4", 4, "35.00"));
        Order order = kit.awaitFinal(ack.orderId());

        assertEquals(OrderStatus.ACCEPTED, order.status());
        assertEquals(6L, kit.wallet.getAvailableShares("u1", "PETR4"));
    }

    @Test
    void invalidRequestIsRejectedImmediatelyWithoutOrder() {
        kit = new OrdersKit("1000.00");

        OrderResult result = kit.service.submit(OrdersKit.buy("u1", "c1", "XXXX9", 1, "10.00"));

        assertEquals(OrderStatus.REJECTED, result.status());
        assertEquals(RejectReason.UNKNOWN_ASSET, result.reason());
        assertNull(result.orderId());
        assertTrue(kit.service.findByUser("u1").isEmpty());
    }

    @Test
    void sameClientOrderIdCreatesOnlyOneOrder() throws Exception {
        kit = new OrdersKit("1000.00");

        OrderResult first = kit.service.submit(OrdersKit.buy("u1", "dup", "PETR4", 1, "100.00"));
        OrderResult second = kit.service.submit(OrdersKit.buy("u1", "dup", "PETR4", 1, "100.00"));
        kit.awaitFinal(first.orderId());

        assertEquals(first.orderId(), second.orderId());
        assertEquals(1, kit.service.findByUser("u1").size());
        assertMoney("100.00", kit.wallet.getReservedBalance("u1"));
    }

    @Test
    void sameClientOrderIdFromDifferentUsersAreDifferentOrders() {
        kit = new OrdersKit("1000.00");

        OrderResult a = kit.service.submit(OrdersKit.buy("u1", "same", "PETR4", 1, "100.00"));
        OrderResult b = kit.service.submit(OrdersKit.buy("u2", "same", "PETR4", 1, "100.00"));

        assertNotEquals(a.orderId(), b.orderId());
    }

    @Test
    void sequenceNumbersFollowArrivalOrder() {
        kit = new OrdersKit("1000000.00");

        kit.service.submit(OrdersKit.buy("u1", "a", "PETR4", 1, "10.00"));
        kit.service.submit(OrdersKit.buy("u1", "b", "PETR4", 1, "10.00"));
        kit.service.submit(OrdersKit.buy("u1", "c", "PETR4", 1, "10.00"));

        List<Order> orders = kit.service.findByUser("u1");
        assertEquals(List.of("a", "b", "c"), orders.stream().map(Order::clientOrderId).toList());
        assertEquals(List.of(1L, 2L, 3L), orders.stream().map(Order::sequenceNumber).toList());
    }

    @Test
    void notifierReceivesFinalStatus() throws Exception {
        List<OrderStatus> seen = new CopyOnWriteArrayList<>();
        kit = new OrdersKit("1000.00", order -> seen.add(order.status()));

        OrderResult ack = kit.service.submit(OrdersKit.buy("u1", "c1", "PETR4", 1, "100.00"));
        kit.awaitFinal(ack.orderId());

        long deadline = System.currentTimeMillis() + 2000;
        while (seen.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(List.of(OrderStatus.ACCEPTED), seen);
    }

    @Test
    void processorSurvivesNotifierFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        kit = new OrdersKit("1000.00", order -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("falha simulada ao notificar");
            }
        });

        OrderResult first = kit.service.submit(OrdersKit.buy("u1", "c1", "PETR4", 1, "100.00"));
        OrderResult second = kit.service.submit(OrdersKit.buy("u1", "c2", "PETR4", 1, "100.00"));

        assertEquals(OrderStatus.ACCEPTED, kit.awaitFinal(first.orderId()).status());
        assertEquals(OrderStatus.ACCEPTED, kit.awaitFinal(second.orderId()).status());
    }

    @Test
    void orderIsOnlyVisibleToItsOwner() {
        kit = new OrdersKit("1000.00");
        OrderResult result = kit.service.submit(OrdersKit.buy("u1", "c1", "PETR4", 1, "35.00"));

        assertTrue(kit.service.findByIdForUser(result.orderId(), "u1").isPresent());
        assertTrue(kit.service.findByIdForUser(result.orderId(), "u2").isEmpty());
        assertTrue(kit.service.findByIdForUser(null, "u1").isEmpty());
    }

    @Test
    void walletFailureRejectsOnlyThatOrderAndProcessorKeepsRunning() throws Exception {
        var failingWallet = new WalletPort() {
            final AtomicInteger calls = new AtomicInteger();
            public BigDecimal getAvailableBalance(String u) { return BigDecimal.ZERO; }
            public long getAvailableShares(String u, String s) { return 0; }
            public boolean reserveFunds(String u, BigDecimal a) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("banco indisponível");
                }
                return true;
            }
            public boolean reserveShares(String u, String s, long q) { return true; }
            public void releaseFunds(String u, BigDecimal a) { }
            public void releaseShares(String u, String s, long q) { }
        };

        var processor = new OrderProcessor(failingWallet, order -> { });
        var service = new OrderService(new OrderValidator(new AssetCatalog()), processor);
        processor.start();
        try {
            OrderResult first = service.submit(OrdersKit.buy("u1", "c1", "PETR4", 1, "10.00"));
            OrderResult second = service.submit(OrdersKit.buy("u1", "c2", "PETR4", 1, "10.00"));

            long deadline = System.currentTimeMillis() + 5000;
            while (service.findById(second.orderId()).orElseThrow().status() == OrderStatus.RECEIVED
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(RejectReason.INTERNAL_ERROR, service.findById(first.orderId()).orElseThrow().rejectReason());
            assertEquals(OrderStatus.ACCEPTED, service.findById(second.orderId()).orElseThrow().status());
        } finally {
            processor.shutdown();
        }
    }
}
