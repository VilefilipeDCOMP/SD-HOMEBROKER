package br.ufs.simulador_bolsa.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.orders.OrderEntity;
import br.ufs.simulador_bolsa.orders.OrderRepository;

@ExtendWith(MockitoExtension.class)
class ReservationReconcilerTest {

    @Mock
    private DatabaseWalletService wallet;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private ReservationReconciler reconciler;

    @AfterEach
    void tearDown() {
        reconciler.shutdown();
    }

    @Test
    void retriesThenReconcilesFromPersistedOpenOrders() throws InterruptedException {
        AssetEntity petr4 = AssetEntity.builder().ticker("PETR4").build();
        User buyer = User.builder().id(1L).username("buyer").build();

        when(orderRepository.findOpenOrdersByUsername("buyer", "ABERTA")).thenReturn(List.of(
                pendingOrder(buyer, petr4, "COMPRA", 4, new BigDecimal("10.00")),
                pendingOrder(buyer, petr4, "VENDA", 3, new BigDecimal("10.00")),
                pendingOrder(buyer, petr4, "COMPRA", 0, new BigDecimal("10.00"))));

        CountDownLatch reconciled = new CountDownLatch(1);
        doAnswer(invocation -> {
            reconciled.countDown();
            return null;
        }).when(wallet).replaceReservations(anyString(), any(), anyMap());

        AtomicInteger attempts = new AtomicInteger();
        Runnable release = () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("RELEASE_STILL_FAILING");
        };

        reconciler.enqueue("FUNDS", "buyer", release);

        assertTrue(reconciled.await(3, TimeUnit.SECONDS));
        ArgumentCaptor<Map<String, Long>> shares = ArgumentCaptor.forClass(Map.class);
        verify(wallet).replaceReservations(eq("buyer"), eq(new BigDecimal("40.00")), shares.capture());
        assertEquals(Map.of("PETR4", 3L), shares.getValue());
        assertEquals(3, attempts.get());
    }

    @Test
    void stopsRetryingWhenReleaseSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        Runnable release = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("TRANSIENT_FAILURE");
            }
        };

        reconciler.enqueue("SHARES", "seller", release);

        verify(wallet, after(1000).never()).replaceReservations(anyString(), any(), anyMap());
        assertEquals(2, attempts.get());
    }

    private static OrderEntity pendingOrder(User user, AssetEntity asset, String side, int remaining, BigDecimal price) {
        return OrderEntity.builder()
                .usuario(user)
                .ativo(asset)
                .tipo(side)
                .quantidade(remaining)
                .quantidadeExecutada(0)
                .precoAlvo(price)
                .status("ABERTA")
                .build();
    }
}
