package br.ufs.simulador_bolsa.orders;

import br.ufs.simulador_bolsa.wallet.InMemoryWallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderPersistenceFailureTest {

    private InMemoryWallet wallet;
    private MatchingEngine engine;
    private OrderProcessor processor;
    private OrderPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        wallet = new InMemoryWallet(new BigDecimal("1000.00"));
        engine = mock(MatchingEngine.class);
        persistenceService = mock(OrderPersistenceService.class);

        processor = new OrderProcessor(wallet, order -> {}, engine);

        ReflectionTestUtils.setField(
                processor, "persistenceService", persistenceService
        );
    }

    @Test
    void buyPersistenceFailureReleasesReservedFunds() {
        Order order = new Order(
                OrdersKit.buy("u1", "c1", "PETR4", 10, "10.00"),
                1L
        );

        when(persistenceService.persistOpenOrder(order))
                .thenThrow(new IllegalStateException("Falha no banco"));

        processor.process(order);

        assertEquals(OrderStatus.REJECTED, order.status());
        assertEquals(RejectReason.INTERNAL_ERROR, order.rejectReason());
        assertNull(order.databaseId());

        assertEquals(0, new BigDecimal("1000.00")
                .compareTo(wallet.getAvailableBalance("u1")));

        assertEquals(0, BigDecimal.ZERO
                .compareTo(wallet.getReservedBalance("u1")));

        verify(engine, never()).match(any(Order.class));
    }

    @Test
    void cancellationPersistenceFailureKeepsOrderAndReservation() {
        MatchingEngine realEngine = new MatchingEngine(
                trade -> {},
                wallet,
                order -> {}
        );

        ReflectionTestUtils.setField(
                realEngine, "persistenceService", persistenceService
        );

        Order order = new Order(
                OrdersKit.buy("u1", "c1", "PETR4", 10, "10.00"),
                1L
        );

        assertTrue(wallet.reserveFunds("u1", order.totalValue()));

        order.assignDatabaseId(1L);
        order.accept();

        realEngine.match(order);

        var snapshotBefore = realEngine.getBookSnapshot("PETR4");

        doThrow(new IllegalStateException("Falha no banco"))
                .doNothing()
                .when(persistenceService)
                .markOrderCancelled(order);

        assertThrows(IllegalStateException.class,
                () -> realEngine.cancelFromBook(order));

        assertEquals(OrderStatus.ACCEPTED, order.status());

        assertEquals(snapshotBefore,
                realEngine.getBookSnapshot("PETR4"));

        assertEquals(0, new BigDecimal("100.00")
                .compareTo(wallet.getReservedBalance("u1")));

        assertEquals(0, new BigDecimal("900.00")
                .compareTo(wallet.getAvailableBalance("u1")));

        assertTrue(realEngine.cancelFromBook(order));

        assertEquals(OrderStatus.CANCELLED, order.status());

        assertEquals(0, BigDecimal.ZERO
                .compareTo(wallet.getReservedBalance("u1")));

        assertEquals(0, new BigDecimal("1000.00")
                .compareTo(wallet.getAvailableBalance("u1")));
    }

    @Test
    void sellPersistenceFailureReleasesReservedShares() {
        wallet.depositShares("u1", "PETR4", 20);

        Order order = new Order(
                OrdersKit.sell("u1", "c1", "PETR4", 10, "10.00"),
                1L
        );

        when(persistenceService.persistOpenOrder(order))
                .thenThrow(new IllegalStateException("Falha no banco"));

        processor.process(order);

        assertEquals(OrderStatus.REJECTED, order.status());
        assertEquals(RejectReason.INTERNAL_ERROR, order.rejectReason());
        assertNull(order.databaseId());

        assertEquals(20L,
                wallet.getAvailableShares("u1", "PETR4"));

        verify(engine, never()).match(any(Order.class));
    }

    @Test
    void nullDatabaseIdRejectsOrderAndReleasesFunds() {
        Order order = new Order(
                OrdersKit.buy("u1", "c1", "PETR4", 10, "10.00"),
                1L
        );

        when(persistenceService.persistOpenOrder(order))
                .thenReturn(null);

        processor.process(order);

        assertEquals(OrderStatus.REJECTED, order.status());
        assertEquals(RejectReason.INTERNAL_ERROR, order.rejectReason());
        assertNull(order.databaseId());

        assertEquals(0, new BigDecimal("1000.00")
                .compareTo(wallet.getAvailableBalance("u1")));

        assertEquals(0, BigDecimal.ZERO
                .compareTo(wallet.getReservedBalance("u1")));

        verify(engine, never()).match(any(Order.class));
    }
}