package br.ufs.simulador_bolsa.simulation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import br.ufs.simulador_bolsa.communication.MarketEvent;
import br.ufs.simulador_bolsa.communication.MarketEventPublisher;
import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.orders.MatchingEngine;
import br.ufs.simulador_bolsa.orders.Order;
import br.ufs.simulador_bolsa.orders.OrderNotifier;
import br.ufs.simulador_bolsa.orders.OrderProcessor;
import br.ufs.simulador_bolsa.orders.OrderRequest;
import br.ufs.simulador_bolsa.orders.OrderService;
import br.ufs.simulador_bolsa.orders.OrderSide;
import br.ufs.simulador_bolsa.orders.OrderStatus;
import br.ufs.simulador_bolsa.orders.OrderValidator;
import br.ufs.simulador_bolsa.wallet.InMemoryWallet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketSimulationTest {

    private static final String CONSERVATIVE = "npc_conservador";
    private static final String ACTIVE = "npc_ativo";
    private static final String SYMBOL = "PETR4";

    private final AssetCatalog catalog = new AssetCatalog();
    private final AssetRepository assets = mock(AssetRepository.class);
    private final InMemoryWallet wallet = new InMemoryWallet(new BigDecimal("10000.00"));
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final CountDownLatch accepted = new CountDownLatch(1);
    private final OrderNotifier notifier = order -> {
        if (CONSERVATIVE.equals(order.userId()) && SYMBOL.equals(order.symbol()) && order.status() == OrderStatus.ACCEPTED) {
            accepted.countDown();
        }
    };
    private final OrderProcessor processor = new OrderProcessor(wallet, notifier, new MatchingEngine(trade -> { }, wallet, notifier));
    private final OrderService orders = new OrderService(new OrderValidator(catalog), processor);
    private final MarketSimulation simulation = createSimulation();

    @BeforeEach
    void start() {
        processor.start();
    }

    @AfterEach
    void shutdown() {
        processor.shutdown();
    }

    @Test
    void positiveNewsUsesCurrentPriceAndPublishesTheHomeBrokerContract() throws InterruptedException {
        AssetEntity asset = new AssetEntity();
        asset.setPrecoAtual(new BigDecimal("40.00"));
        when(assets.findByTicker(SYMBOL)).thenReturn(Optional.of(asset));
        wallet.depositShares(CONSERVATIVE, SYMBOL, 2);
        EconomicEvent news = new EconomicEvent("Alta", "A demanda aumentou.", SYMBOL, 5);

        simulation.applyEvent(news);
        simulation.submitOrders();
        simulation.submitOrders();
        assertTrue(accepted.await(5, TimeUnit.SECONDS));
        assertEquals(4, orders.findByUser(CONSERVATIVE).size());
        assertEquals(4, orders.findByUser(ACTIVE).size());

        Order conservative = findOrder(CONSERVATIVE, SYMBOL);
        assertEquals(OrderSide.BUY, conservative.side());
        assertEquals(new BigDecimal("42.00"), conservative.price());
        assertEquals(1, conservative.quantity());
        assertEquals(5, findOrder(ACTIVE, SYMBOL).quantity());
        assertEquals(new BigDecimal("35.00"), catalog.findBySymbol(SYMBOL).orElseThrow().currentPrice());
        assertEquals(new BigDecimal("40.00"), asset.getPrecoAtual());

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(eq("/topic/market"), message.capture());
        MarketEvent<?> event = assertInstanceOf(MarketEvent.class, message.getValue());
        assertEquals("ECONOMIC_EVENT", event.type());
        assertNull(event.requestId());
        assertNotNull(event.eventId());
        assertNotNull(event.timestamp());
        assertEquals(news, event.data());
    }

    @Test
    void negativeNewsChangesTheSellLimitAndRespectsAvailableShares() {
        wallet.depositShares(ACTIVE, SYMBOL, 2);
        simulation.applyEvent(new EconomicEvent("Queda", "A demanda caiu.", SYMBOL, -5));
        simulation.submitOrders();

        assertTrue(orders.findByUser(CONSERVATIVE).stream().noneMatch(order -> SYMBOL.equals(order.symbol())));
        Order sell = findOrder(ACTIVE, SYMBOL);
        assertEquals(OrderSide.SELL, sell.side());
        assertEquals(new BigDecimal("33.25"), sell.price());
        assertEquals(2, sell.quantity());
        assertEquals(new BigDecimal("68.00"), findOrder(ACTIVE, "VALE3").price());
    }

    @Test
    void brokerFailureDoesNotPreventAgentsFromReacting() {
        doThrow(new IllegalStateException("Broker indisponível"))
                .when(messaging).convertAndSend(eq("/topic/market"), any(Object.class));

        simulation.applyEvent(new EconomicEvent("Alta", "A demanda aumentou.", SYMBOL, 5));
        simulation.submitOrders();

        assertEquals(new BigDecimal("36.75"), findOrder(CONSERVATIVE, SYMBOL).price());
    }

    @Test
    void keepsOpenOrderAlreadyRegisteredForTheNpc() throws InterruptedException {
        var openOrder = orders.submit(new OrderRequest(CONSERVATIVE, "recovered-42", SYMBOL,
                OrderSide.BUY, 1L, new BigDecimal("35.00")));
        assertTrue(accepted.await(5, TimeUnit.SECONDS));

        simulation.submitOrders();

        var ordersForSymbol = orders.findByUser(CONSERVATIVE).stream()
                .filter(order -> SYMBOL.equals(order.symbol()))
                .map(Order::orderId)
                .toList();
        assertEquals(List.of(openOrder.orderId()), ordersForSymbol);
        assertEquals(OrderSide.BUY, findOrder(ACTIVE, SYMBOL).side());
        assertEquals(OrderSide.BUY, findOrder(CONSERVATIVE, "VALE3").side());
    }

    private Order findOrder(String username, String symbol) {
        return orders.findByUser(username).stream().filter(order -> symbol.equals(order.symbol())).findFirst().orElseThrow();
    }

    private MarketSimulation createSimulation() {
        NpcAccounts accounts = mock(NpcAccounts.class);
        when(accounts.ensureAccount(CONSERVATIVE)).thenReturn(CONSERVATIVE);
        when(accounts.ensureAccount(ACTIVE)).thenReturn(ACTIVE);
        return new MarketSimulation(accounts, catalog, assets, wallet, orders, new MarketEventPublisher(messaging));
    }
}
