package br.ufs.simulador_bolsa.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import tools.jackson.databind.JsonNode;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.websocket.allowed-origins=http://localhost:5173"
)
class OrderStompIntegrationTest {

    @LocalServerPort
    private int port;

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
        StompSession session = client.connectAsync(
                "ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() { }
        ).get(5, TimeUnit.SECONDS);
        session.setAutoReceipt(true);
        return session;
    }

    private void subscribeAndWait(StompSession session, String destination, BlockingQueue<JsonNode> sink)
            throws Exception {
        CountDownLatch receipt = new CountDownLatch(1);
        session.subscribe(destination, collector(sink)).addReceiptTask(receipt::countDown);
        assertTrue(receipt.await(5, TimeUnit.SECONDS), "inscrição não confirmada: " + destination);
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

                session.send("/assets/list", Map.of());

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
