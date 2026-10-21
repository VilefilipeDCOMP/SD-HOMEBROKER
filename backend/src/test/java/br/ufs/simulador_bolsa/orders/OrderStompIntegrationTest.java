package br.ufs.simulador_bolsa.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

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
import br.ufs.simulador_bolsa.auth.service.AuthService;

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
    @Qualifier("brokerChannel")
    private AbstractSubscribableChannel brokerChannel;

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
            } finally {
                session.disconnect();
            }
        } finally {
            client.stop();
        }
    }
}
