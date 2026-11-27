package br.ufs.simulador_bolsa.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.observability.MarketMetricsService;
import br.ufs.simulador_bolsa.wallet.DatabaseWalletService;

/** Regressão da recuperação: identidade persistida, saldo reservado e chave por username. */
class OrderRecoveryRegressionTest {

    private static User user(long id, String username) {
        return User.builder().id(id).username(username)
                .saldo(new BigDecimal("10000.00")).build();
    }

    private static AssetEntity asset() {
        return AssetEntity.builder().id(3L).ticker("ITUB4")
                .nomeEmpresa("Itaú").precoAtual(new BigDecimal("35.00")).build();
    }

    private static OrderEntity partialOrder() {
        return OrderEntity.builder()
                .id(42L)
                .usuario(user(7L, "ana"))
                .ativo(asset())
                .tipo("COMPRA")
                .quantidade(6)
                .quantidadeExecutada(4)
                .precoAlvo(new BigDecimal("35.00"))
                .status("ABERTA")
                .clientOrderId("cid-original")
                .orderId("order-uuid-original")
                .build();
    }

    @Test
    void recoveryPreservesPersistedIdsAndReservesOnlyRemainingQuantity() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderPersistenceService persistence = mock(OrderPersistenceService.class);
        OrderService orderService = new OrderService(mock(OrderValidator.class), mock(OrderProcessor.class));
        MatchingEngine engine = mock(MatchingEngine.class);
        DatabaseWalletService wallet = mock(DatabaseWalletService.class);
        MarketMetricsService metrics = mock(MarketMetricsService.class);

        when(repo.findByStatusWithAssetAndUser("ABERTA")).thenReturn(List.of(partialOrder()));
        when(wallet.reserveFunds(anyString(), any())).thenReturn(true);

        OrderRecoveryRunner runner = new OrderRecoveryRunner(
                repo, persistence, orderService, engine, wallet, metrics);
        List<Map<String, Object>> recovered = runner.recoverOpenOrders();

        assertEquals(1, recovered.size());
        Map<String, Object> item = recovered.getFirst();
        assertEquals("order-uuid-original", item.get("orderId"));
        assertEquals("cid-original", item.get("clientOrderId"));
        // A coluna quantidade guarda o saldo; a executada fica apenas no banco.
        assertEquals(6L, item.get("quantity"));
        assertEquals(4L, item.get("executedQuantity"));
        assertEquals(6L, item.get("remainingQuantity"));

        ArgumentCaptor<Order> restored = ArgumentCaptor.forClass(Order.class);
        verify(engine).restoreOrder(restored.capture());
        assertEquals("order-uuid-original", restored.getValue().orderId());
        assertEquals("cid-original", restored.getValue().clientOrderId());
        assertEquals(6L, restored.getValue().quantity());
        assertEquals(6L, restored.getValue().remainingQuantity());
        assertEquals(42L, restored.getValue().databaseId());
        assertEquals(OrderStatus.ACCEPTED, restored.getValue().status());

        // Reserva por username e somente sobre o saldo ainda não executado.
        verify(wallet).reserveFunds("ana", new BigDecimal("210.00"));
        assertEquals(1, orderService.findByUser("ana").size());
    }

    @Test
    void recoveryUsesSyntheticIdsForLegacyRows() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderPersistenceService persistence = mock(OrderPersistenceService.class);
        OrderService orderService = new OrderService(mock(OrderValidator.class), mock(OrderProcessor.class));
        MatchingEngine engine = mock(MatchingEngine.class);
        DatabaseWalletService wallet = mock(DatabaseWalletService.class);
        MarketMetricsService metrics = mock(MarketMetricsService.class);

        OrderEntity legacy = partialOrder();
        legacy.setOrderId(null);
        legacy.setClientOrderId(null);
        when(repo.findByStatusWithAssetAndUser("ABERTA")).thenReturn(List.of(legacy));
        when(wallet.reserveFunds(anyString(), any())).thenReturn(true);

        OrderRecoveryRunner runner = new OrderRecoveryRunner(
                repo, persistence, orderService, engine, wallet, metrics);
        List<Map<String, Object>> recovered = runner.recoverOpenOrders();

        assertEquals("db-42", recovered.getFirst().get("orderId"));
        assertEquals("recovered-42", recovered.getFirst().get("clientOrderId"));
    }
}
