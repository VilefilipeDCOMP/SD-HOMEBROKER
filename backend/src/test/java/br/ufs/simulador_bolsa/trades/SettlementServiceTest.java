package br.ufs.simulador_bolsa.trades;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.observability.MarketMetricsService;
import br.ufs.simulador_bolsa.orders.Order;
import br.ufs.simulador_bolsa.orders.OrderEntity;
import br.ufs.simulador_bolsa.orders.OrderRepository;
import br.ufs.simulador_bolsa.orders.OrderRequest;
import br.ufs.simulador_bolsa.orders.OrderSide;
import br.ufs.simulador_bolsa.orders.OrderStatus;
import br.ufs.simulador_bolsa.orders.Trade;
import br.ufs.simulador_bolsa.wallet.DatabaseWalletService;
import br.ufs.simulador_bolsa.wallet.PortfolioEntity;
import br.ufs.simulador_bolsa.wallet.PortfolioRepository;
import br.ufs.simulador_bolsa.wallet.ReservationReconciler;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private PortfolioRepository portfolioRepository;

    @Mock
    private TradeRepository tradeRepository;

    @Mock
    private DatabaseWalletService walletService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private ReservationReconciler reservationReconciler;

    @InjectMocks
    private SettlementService settlementService;

    private User buyer;
    private User seller;
    private AssetEntity petr4;
    private OrderEntity buyOrder;
    private OrderEntity sellOrder;

    @BeforeEach
    void setUp() {
        buyer = User.builder()
                .id(1L)
                .username("comprador")
                .saldo(new BigDecimal("10000.00"))
                .build();

        seller = User.builder()
                .id(2L)
                .username("vendedor")
                .saldo(new BigDecimal("5000.00"))
                .build();

        petr4 = AssetEntity.builder()
                .id(10L)
                .ticker("PETR4")
                .nomeEmpresa("Petrobras")
                .precoAtual(new BigDecimal("35.00"))
                .build();

        buyOrder = OrderEntity.builder()
                .id(100L)
                .usuario(buyer)
                .ativo(petr4)
                .tipo("COMPRA")
                .quantidade(10)
                .precoAlvo(new BigDecimal("36.00")) // comprador aceitava até 36.00
                .status("ABERTA")
                .build();

        sellOrder = OrderEntity.builder()
                .id(200L)
                .usuario(seller)
                .ativo(petr4)
                .tipo("VENDA")
                .quantidade(10)
                .precoAlvo(new BigDecimal("35.00"))
                .status("ABERTA")
                .build();
    }

    @Test
    @DisplayName("Liquidação atômica deve transferir saldo, ações, devolver sobra e calcular preço médio ponderado")
    void testAtomicSettlementAndSurplusRefund() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(200L)).thenReturn(Optional.of(sellOrder));
        when(userRepository.findById(1L)).thenReturn(Optional.of(buyer));
        when(userRepository.findById(2L)).thenReturn(Optional.of(seller));
        when(assetRepository.findByTicker("PETR4")).thenReturn(Optional.of(petr4));

        PortfolioEntity sellerPortfolio = PortfolioEntity.builder()
                .usuario(seller)
                .ativo(petr4)
                .quantidade(20)
                .precoMedio(new BigDecimal("30.00"))
                .build();
        when(portfolioRepository.findByUsuarioIdAndTicker(2L, "PETR4")).thenReturn(Optional.of(sellerPortfolio));

        // Comprador já tinha 10 cotas a 30.00 cada (total 300)
        PortfolioEntity buyerPortfolio = PortfolioEntity.builder()
                .usuario(buyer)
                .ativo(petr4)
                .quantidade(10)
                .precoMedio(new BigDecimal("30.00"))
                .build();
        when(portfolioRepository.findByUsuarioIdAndTicker(1L, "PETR4")).thenReturn(Optional.of(buyerPortfolio));

        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Casamento de 10 ações executado a 34.00 (abaixo do alvo de 36.00 do comprador)
        TradeExecutionCommand cmd = new TradeExecutionCommand(
                100L,
                200L,
                "PETR4",
                10,
                new BigDecimal("34.00")
        );

        TradeEntity trade = settlementService.settleTrade(cmd);

        assertNotNull(trade);
        assertEquals(10, trade.getQuantidadeExecutada());
        assertEquals(new BigDecimal("34.00"), trade.getPrecoExecutado());

        // Financeiro: 10 x 34.00 = 340.00
        // Comprador: 10000 - 340 = 9660.00
        assertEquals(new BigDecimal("9660.00"), buyer.getSaldo());
        // Vendedor: 5000 + 340 = 5340.00
        assertEquals(new BigDecimal("5340.00"), seller.getSaldo());

        // Devolução da reserva de sobra:
        // O comprador reservou 10 * 36.00 = 360.00
        verify(walletService).releaseFunds("comprador", new BigDecimal("360.00"));

        // Custódia vendedor: tinha 20, vendeu 10 -> sobram 10
        assertEquals(10, sellerPortfolio.getQuantidade());
        verify(walletService).releaseShares("vendedor", "PETR4", 10);

        // Preço Médio ponderado do comprador:
        // Antigo: 10 @ 30.00 = 300.00
        // Novo: 10 @ 34.00 = 340.00
        // Total: 20 cotas por 640.00 -> PM = 32.00
        assertEquals(20, buyerPortfolio.getQuantidade());
        assertEquals(new BigDecimal("32.00"), buyerPortfolio.getPrecoMedio());

        // Ordens marcadas como EXECUTADA
        assertEquals("EXECUTADA", buyOrder.getStatus());
        assertEquals("EXECUTADA", sellOrder.getStatus());
    }

    @Test
    @DisplayName("Autonegociação deve ser rejeitada para o mesmo investidor")
    void testSelfTradeIsRejected() {
        sellOrder.setUsuario(buyer); // vendedor é a mesma pessoa que o comprador

        when(orderRepository.findById(100L)).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(200L)).thenReturn(Optional.of(sellOrder));

        TradeExecutionCommand cmd = new TradeExecutionCommand(100L, 200L, "PETR4", 5, new BigDecimal("35.00"));

        assertThrows(IllegalStateException.class, () -> settlementService.settleTrade(cmd));
        verifyNoInteractions(tradeRepository);
    }

    @Test
    @DisplayName("Falha no INSERT de historico_negociacoes deve abortar transação sem liberar reservas em memória")
    void testRollbackDoesNotReleaseReservations() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(200L)).thenReturn(Optional.of(sellOrder));
        when(userRepository.findById(1L)).thenReturn(Optional.of(buyer));
        when(userRepository.findById(2L)).thenReturn(Optional.of(seller));
        when(assetRepository.findByTicker("PETR4")).thenReturn(Optional.of(petr4));

        PortfolioEntity sellerPortfolio = PortfolioEntity.builder()
                .usuario(seller)
                .ativo(petr4)
                .quantidade(20)
                .precoMedio(new BigDecimal("30.00"))
                .build();
        when(portfolioRepository.findByUsuarioIdAndTicker(2L, "PETR4")).thenReturn(Optional.of(sellerPortfolio));
        when(portfolioRepository.findByUsuarioIdAndTicker(1L, "PETR4")).thenReturn(Optional.empty());

        // Força erro no INSERT de historico_negociacoes
        when(tradeRepository.save(any(TradeEntity.class))).thenThrow(new RuntimeException("DB_INSERT_FAILURE"));

        TradeExecutionCommand cmd = new TradeExecutionCommand(100L, 200L, "PETR4", 10, new BigDecimal("34.00"));

        assertThrows(RuntimeException.class, () -> settlementService.settleTrade(cmd));

        // Como a transação falhou antes do commit, a liberação de fundos/ações NÃO deve ocorrer
        verify(walletService, never()).releaseFunds(anyString(), any(BigDecimal.class));
        verify(walletService, never()).releaseShares(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Falha na liberação após commit deve agendar reconciliação sem abortar as demais liberações")
    void testFailedReleaseSchedulesReconciliation() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(200L)).thenReturn(Optional.of(sellOrder));
        when(userRepository.findById(1L)).thenReturn(Optional.of(buyer));
        when(userRepository.findById(2L)).thenReturn(Optional.of(seller));
        when(assetRepository.findByTicker("PETR4")).thenReturn(Optional.of(petr4));

        when(portfolioRepository.findByUsuarioIdAndTicker(2L, "PETR4"))
                .thenReturn(Optional.of(PortfolioEntity.builder()
                        .usuario(seller).ativo(petr4).quantidade(20)
                        .precoMedio(new BigDecimal("30.00")).build()));
        when(portfolioRepository.findByUsuarioIdAndTicker(1L, "PETR4"))
                .thenReturn(Optional.of(PortfolioEntity.builder()
                        .usuario(buyer).ativo(petr4).quantidade(10)
                        .precoMedio(new BigDecimal("30.00")).build()));
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        doThrow(new RuntimeException("RELEASE_FAILURE"))
                .when(walletService).releaseFunds("comprador", new BigDecimal("360.00"));

        TradeExecutionCommand cmd = new TradeExecutionCommand(
                100L, 200L, "PETR4", 10, new BigDecimal("34.00"));

        assertNotNull(settlementService.settleTrade(cmd));

        verify(reservationReconciler).enqueue(eq("FUNDS"), eq("comprador"), any());
        verify(reservationReconciler, never()).enqueue(eq("SHARES"), anyString(), any());
        verify(walletService).releaseShares("vendedor", "PETR4", 10);
    }

    @Test
    @DisplayName("Negociação confirmada gera log TRADE_EXECUTED e métrica somente após o commit")
    void executedLogAndMetricAreRegisteredOnlyAfterCommit() {
        MarketMetricsService metrics = mock(MarketMetricsService.class);
        SettlementService service = settlementSpyWith(metrics);
        Logger logger = (Logger) LoggerFactory.getLogger(SettlementService.class);
        ListAppender<ILoggingEvent> appender = attachAppender(logger);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.settle(persistedTrade());

            assertTrue(noTradeExecutedLog(appender));
            verify(metrics, never()).recordTradeExecuted(anyLong(), any(BigDecimal.class), anyDouble());

            TransactionSynchronizationUtils.triggerAfterCommit();

            assertFalse(noTradeExecutedLog(appender));
            verify(metrics).recordTradeExecuted(eq(10L), eq(new BigDecimal("34.00")), anyDouble());
        } finally {
            logger.detachAppender(appender);
            clearSynchronizationIfActive();
        }
    }

    @Test
    @DisplayName("Rollback da liquidação não gera log TRADE_EXECUTED nem incrementa a métrica")
    void rollbackNeitherLogsTradeExecutedNorRecordsMetric() {
        MarketMetricsService metrics = mock(MarketMetricsService.class);
        SettlementService service = settlementSpyWith(metrics);
        Logger logger = (Logger) LoggerFactory.getLogger(SettlementService.class);
        ListAppender<ILoggingEvent> appender = attachAppender(logger);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.settle(persistedTrade());
            // rollback: descarta as sincronizações sem disparar afterCommit
            TransactionSynchronizationManager.clearSynchronization();

            assertTrue(noTradeExecutedLog(appender));
            verify(metrics, never()).recordTradeExecuted(anyLong(), any(BigDecimal.class), anyDouble());
        } finally {
            logger.detachAppender(appender);
            clearSynchronizationIfActive();
        }
    }

    private SettlementService settlementSpyWith(MarketMetricsService metrics) {
        SettlementService spy = spy(settlementService);
        ReflectionTestUtils.setField(spy, "metricsService", metrics);
        doReturn(TradeEntity.builder().id(99L).build()).when(spy).settleTrade(any(TradeExecutionCommand.class));
        return spy;
    }

    private static Trade persistedTrade() {
        Order buy = new Order("buy-original",
                new OrderRequest("comprador", "cid-b", "PETR4", OrderSide.BUY, 10L, new BigDecimal("34.00")),
                1L, OrderStatus.ACCEPTED, 100L);
        Order sell = new Order("sell-original",
                new OrderRequest("vendedor", "cid-s", "PETR4", OrderSide.SELL, 10L, new BigDecimal("34.00")),
                2L, OrderStatus.ACCEPTED, 200L);
        return new Trade(buy, sell, 10, new BigDecimal("34.00"));
    }

    private static ListAppender<ILoggingEvent> attachAppender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static boolean noTradeExecutedLog(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.startsWith("TRADE_EXECUTED"));
    }

    private static void clearSynchronizationIfActive() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Settle com ordens sem databaseId persistido deve sinalizar erro ao motor")
    void testSettleWithoutPersistedDatabaseIdThrowsException() {
        br.ufs.simulador_bolsa.orders.Order memBuy = new br.ufs.simulador_bolsa.orders.Order(
                new br.ufs.simulador_bolsa.orders.OrderRequest("buyer", "cid-b", "PETR4", br.ufs.simulador_bolsa.orders.OrderSide.BUY, 10L, new BigDecimal("35.00")),
                1L
        );
        br.ufs.simulador_bolsa.orders.Order memSell = new br.ufs.simulador_bolsa.orders.Order(
                new br.ufs.simulador_bolsa.orders.OrderRequest("seller", "cid-s", "PETR4", br.ufs.simulador_bolsa.orders.OrderSide.SELL, 10L, new BigDecimal("35.00")),
                2L
        );

        // Nenhuma das ordens possui databaseId
        assertNull(memBuy.databaseId());
        assertNull(memSell.databaseId());

        br.ufs.simulador_bolsa.orders.Trade trade = new br.ufs.simulador_bolsa.orders.Trade(memBuy, memSell, 10L, new BigDecimal("35.00"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> settlementService.settle(trade));
        assertTrue(ex.getMessage().contains("sem ID persistido"));
    }
}
