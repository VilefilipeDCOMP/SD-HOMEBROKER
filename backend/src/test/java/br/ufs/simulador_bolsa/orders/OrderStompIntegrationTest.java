package br.ufs.simulador_bolsa.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import tools.jackson.databind.JsonNode;
import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.auth.service.AuthService;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.trades.TradeEntity;
import br.ufs.simulador_bolsa.trades.TradeRepository;
import br.ufs.simulador_bolsa.wallet.PortfolioEntity;
import br.ufs.simulador_bolsa.wallet.PortfolioId;
import br.ufs.simulador_bolsa.wallet.PortfolioRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.websocket.allowed-origins=http://localhost:5173"
)
class OrderStompIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private OrderPersistenceService persistenceService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderProcessor orderProcessor;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    @Qualifier("brokerChannel")
    private AbstractSubscribableChannel brokerChannel;

    private final List<Long> createdUserIds = new CopyOnWriteArrayList<>();
    private final List<String> createdUsernames = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanupStompOrders() {
        orderProcessor.awaitIdle();
        for (String username : createdUsernames) {
            for (Order order : orderService.findByUser(username)) {
                orderService.cancelOrder(order.orderId(), username);
            }
        }
        orderProcessor.awaitIdle();
        for (Long userId : createdUserIds) {
            List<TradeEntity> userTrades = tradeRepository.findByUsuarioId(userId);
            if (!userTrades.isEmpty()) {
                tradeRepository.deleteAll(userTrades);
            }
        }
        for (Long userId : createdUserIds) {
            List<OrderEntity> userOrders = orderRepository.findByUsuarioIdOrderByCriadoEmDesc(userId);
            if (!userOrders.isEmpty()) {
                orderRepository.deleteAll(userOrders);
            }
        }
        createdUsernames.clear();
        createdUserIds.clear();
    }

    private StompFrameHandler collector(BlockingQueue<JsonNode> sink) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.add((JsonNode) payload);
            }
        };
    }

    private StompSession connect(WebSocketStompClient client) throws Exception {
        var auth = authService.register("m3_test_" + UUID.randomUUID(), "test-password");
        createdUserIds.add(auth.userId());
        createdUsernames.add(auth.username());
        var headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + auth.token());
        StompSession session = client.connectAsync(
                "ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() { }
        ).get(5, TimeUnit.SECONDS);
        return session;
    }

    private void subscribeAndWait(StompSession session, String destination, BlockingQueue<JsonNode> sink)
            throws Exception {
        String subscriptionId = UUID.randomUUID().toString();
        CountDownLatch subscribed = new CountDownLatch(1);
        // O broker simples não envia RECEIPT; aguardamos o registro real da inscrição.
        var interceptor = new ExecutorChannelInterceptor() {
            @Override
            public void afterMessageHandled(Message<?> message, MessageChannel channel,
                    MessageHandler handler, Exception exception) {
                var headers = message.getHeaders();
                if (handler instanceof SimpleBrokerMessageHandler
                        && SimpMessageHeaderAccessor.getMessageType(headers) == SimpMessageType.SUBSCRIBE
                        && subscriptionId.equals(SimpMessageHeaderAccessor.getSubscriptionId(headers))
                        && exception == null) {
                    subscribed.countDown();
                }
            }
        };
        brokerChannel.addInterceptor(interceptor);
        try {
            var headers = new StompHeaders();
            headers.setDestination(destination);
            headers.setId(subscriptionId);
            session.subscribe(headers, collector(sink));
            assertTrue(subscribed.await(5, TimeUnit.SECONDS), "inscrição não registrada: " + destination);
        } finally {
            brokerChannel.removeInterceptor(interceptor);
        }
    }

    private JsonNode waitFor(BlockingQueue<JsonNode> messages, Predicate<JsonNode> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode message = messages.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && condition.test(message)) {
                return message;
            }
        }
        throw new AssertionError("Mensagem esperada não chegou em 5s");
    }

    @Test
    void validOrderReturnsAckAndThenAccepted() throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        try {
            StompSession session = connect(client);
            try {
                var messages = new LinkedBlockingQueue<JsonNode>();
                subscribeAndWait(session, "/user/queue/orders", messages);

                session.send("/app/orders/create", Map.of(
                        "clientOrderId", "it-1", "symbol", "PETR4",
                        "side", "BUY", "quantity", 1, "price", "35.00"));

                JsonNode update = waitFor(messages, m ->
                        "ORDER_UPDATED".equals(m.get("type").asString())
                                && "ACCEPTED".equals(m.get("data").get("status").asString()));

                assertEquals("it-1", update.get("requestId").asString());
                assertEquals("PETR4", update.get("data").get("symbol").asString());
            } finally {
                session.disconnect();
            }
        } finally {
            client.stop();
        }
    }

    @Test
    void orderWithUnknownAssetIsRejectedInTheAck() throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        try {
            StompSession session = connect(client);
            try {
                var messages = new LinkedBlockingQueue<JsonNode>();
                subscribeAndWait(session, "/user/queue/orders", messages);

                session.send("/app/orders/create", Map.of(
                        "clientOrderId", "it-2", "symbol", "XXXX9",
                        "side", "BUY", "quantity", 1, "price", "10.00"));

                JsonNode ack = waitFor(messages, m -> "ORDER_ACK".equals(m.get("type").asString()));
                assertEquals("REJECTED", ack.get("data").get("status").asString());
                assertEquals("UNKNOWN_ASSET", ack.get("data").get("reason").asString());
            } finally {
                session.disconnect();
            }
        } finally {
            client.stop();
        }
    }

    @Test
    void malformedMessageIsRejectedAndConnectionStaysOpen() throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        try {
            StompSession session = connect(client);
            try {
                var messages = new LinkedBlockingQueue<JsonNode>();
                subscribeAndWait(session, "/user/queue/orders", messages);

                session.send("/app/orders/create", Map.of(
                        "clientOrderId", "it-3", "symbol", "PETR4",
                        "side", "BUY", "quantity", "abc", "price", "10.00"));

                JsonNode ack = waitFor(messages, m -> "ORDER_ACK".equals(m.get("type").asString()));
                assertEquals("INVALID_FIELDS", ack.get("data").get("reason").asString());
                assertTrue(session.isConnected());
            } finally {
                session.disconnect();
            }
        } finally {
            client.stop();
        }
    }

    @Test
    void assetListIsDeliveredOnlyToTheRequester() throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        try {
            StompSession session = connect(client);
            try {
                var messages = new LinkedBlockingQueue<JsonNode>();
                subscribeAndWait(session, "/user/queue/replies", messages);

                session.send("/app/assets/list", Map.of());

                JsonNode list = waitFor(messages, m -> "ASSET_LIST".equals(m.get("type").asString()));
                assertEquals(4, list.get("data").size());
                assertEquals("ITUB4", list.get("data").get(0).get("symbol").asString());
                assertTrue(list.get("data").get(0).has("volume"));
            } finally {
                session.disconnect();
            }
        } finally {
            client.stop();
        }
    }

    @Test
    void disconnectedThirdClientReceivesTradeVolumeOnceUponReconnect() throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        try {
            var buyerAuth = authService.register("buyer_vol_" + UUID.randomUUID(), "test-password");
            var sellerAuth = authService.register("seller_vol_" + UUID.randomUUID(), "test-password");
            var observerAuth = authService.register("observer_vol_" + UUID.randomUUID(), "test-password");
            createdUserIds.addAll(List.of(buyerAuth.userId(), sellerAuth.userId(), observerAuth.userId()));
            createdUsernames.addAll(List.of(buyerAuth.username(), sellerAuth.username(), observerAuth.username()));

            // concede acoes ao vendedor para viabilizar a negociacao real
            User sellerUser = userRepository.findById(sellerAuth.userId()).orElseThrow();
            AssetEntity mglu3 = persistenceService.ensureAsset("MGLU3");
            PortfolioEntity sellerPos = new PortfolioEntity();
            sellerPos.setId(new PortfolioId(sellerUser.getId(), mglu3.getId()));
            sellerPos.setUsuario(sellerUser);
            sellerPos.setAtivo(mglu3);
            sellerPos.setQuantidade(50);
            sellerPos.setPrecoMedio(new BigDecimal("2.00"));
            portfolioRepository.save(sellerPos);

            StompSession buyerSession = connectWithToken(client, buyerAuth.token());
            StompSession sellerSession = connectWithToken(client, sellerAuth.token());
            StompSession observerSession = connectWithToken(client, observerAuth.token());

            long volumeAntes;
            try {
                var observerReplies = new LinkedBlockingQueue<JsonNode>();
                subscribeAndWait(observerSession, "/user/queue/replies", observerReplies);
                observerSession.send("/app/assets/list", Map.of());
                JsonNode initialList = waitFor(observerReplies, m -> "ASSET_LIST".equals(m.get("type").asString()));
                volumeAntes = extractVolume(initialList.get("data"), "MGLU3");

                // terceiro cliente desconecta antes da negociacao acontecer
                observerSession.disconnect();

                var marketMessages = new LinkedBlockingQueue<JsonNode>();
                subscribeAndWait(buyerSession, "/topic/market", marketMessages);

                sellerSession.send("/app/orders/create", Map.of(
                        "clientOrderId", "vol-sell-1", "symbol", "MGLU3",
                        "side", "SELL", "quantity", 15, "price", "2.20"));
                buyerSession.send("/app/orders/create", Map.of(
                        "clientOrderId", "vol-buy-1", "symbol", "MGLU3",
                        "side", "BUY", "quantity", 15, "price", "2.20"));

                JsonNode tradeEvent = waitFor(marketMessages, m ->
                        "TRADE_EXECUTED".equals(m.get("type").asString())
                                && "MGLU3".equals(m.get("data").get("symbol").asString()));
                assertEquals(volumeAntes + 15L, tradeEvent.get("data").get("volume").asLong());

                // terceiro cliente reconecta e pede ASSET_LIST: volume deve incluir a negociacao uma unica vez
                observerSession = connectWithToken(client, observerAuth.token());
                var reconnectReplies = new LinkedBlockingQueue<JsonNode>();
                subscribeAndWait(observerSession, "/user/queue/replies", reconnectReplies);
                observerSession.send("/app/assets/list", Map.of());

                JsonNode reconnectedList = waitFor(reconnectReplies, m -> "ASSET_LIST".equals(m.get("type").asString()));
                assertEquals(volumeAntes + 15L, extractVolume(reconnectedList.get("data"), "MGLU3"));
            } finally {
                if (buyerSession.isConnected()) buyerSession.disconnect();
                if (sellerSession.isConnected()) sellerSession.disconnect();
                if (observerSession.isConnected()) observerSession.disconnect();
            }
        } finally {
            client.stop();
        }
    }

    private StompSession connectWithToken(WebSocketStompClient client, String token) throws Exception {
        var headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return client.connectAsync(
                "ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() { }
        ).get(5, TimeUnit.SECONDS);
    }

    private long extractVolume(JsonNode assetsArray, String symbol) {
        for (JsonNode item : assetsArray) {
            if (symbol.equals(item.get("symbol").asString())) {
                return item.get("volume").asLong();
            }
        }
        throw new AssertionError("Ativo não encontrado em ASSET_LIST: " + symbol);
    }
}
