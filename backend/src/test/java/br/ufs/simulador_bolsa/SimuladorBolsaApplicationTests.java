package br.ufs.simulador_bolsa;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import br.ufs.simulador_bolsa.communication.MarketEventPublisher;
import br.ufs.simulador_bolsa.auth.service.AuthService;
import tools.jackson.databind.JsonNode;


@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.websocket.allowed-origins=http://localhost:5173"
)
class SimuladorBolsaApplicationTests {
	@Autowired
	private MarketEventPublisher publisher;

	@Autowired
	private AuthService authService;

	private StompHeaders authenticatedHeaders() {
		var auth = authService.register("m1_test_" + UUID.randomUUID(), "test-password");
		var headers = new StompHeaders();
		headers.add("Authorization", "Bearer " + auth.token());
		return headers;
	}

	@Test
	void contextLoads() {
	}

	@LocalServerPort
	private int port;

	private StompFrameHandler marketHandler(BlockingQueue<JsonNode> messages) {
		return new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return JsonNode.class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				messages.add((JsonNode) payload);
			}
		};
}

	@Test
	void acceptsWebSocketConnection() throws Exception {
		try (var client = HttpClient.newHttpClient()) {
			var socket = client.newWebSocketBuilder()
					.header("Origin", "http://localhost:5173")
					.buildAsync(
							URI.create("ws://localhost:" + port + "/ws"),
							new WebSocket.Listener() {}
					)
					.get(5, TimeUnit.SECONDS);

			try {
				assertFalse(socket.isOutputClosed());
			} finally {
				socket.abort();
			}
		}
	}

	@Test
	void acceptsStompConnection() throws Exception {
		var client = new WebSocketStompClient(new StandardWebSocketClient());

		try {
			var session = client.connectAsync(
					"ws://localhost:" + port + "/ws",
					new WebSocketHttpHeaders(), authenticatedHeaders(),
					new StompSessionHandlerAdapter() {}
			).get(5, TimeUnit.SECONDS);

			try {
				assertTrue(session.isConnected());
			} finally {
				session.disconnect();
			}
		} finally {
			client.stop();
		}
	}

	@Test
	void acceptsTwoSimultaneousStompConnections() throws Exception {
		var client = new WebSocketStompClient(new StandardWebSocketClient());

		client.setMessageConverter(new JacksonJsonMessageConverter());

		try {
			var first = client.connectAsync(
					"ws://localhost:" + port + "/ws",
					new WebSocketHttpHeaders(), authenticatedHeaders(),
					new StompSessionHandlerAdapter() {}
			).get(5, TimeUnit.SECONDS);

			try {
				var second = client.connectAsync(
						"ws://localhost:" + port + "/ws",
							new WebSocketHttpHeaders(), authenticatedHeaders(),
						new StompSessionHandlerAdapter() {}
				).get(5, TimeUnit.SECONDS);

				try {
					assertTrue(first.isConnected());
					assertTrue(second.isConnected());
					assertNotEquals(first.getSessionId(), second.getSessionId());

					var firstMessages = new LinkedBlockingQueue<JsonNode>();
					var secondMessages = new LinkedBlockingQueue<JsonNode>();

					first.subscribe("/topic/market", marketHandler(firstMessages));
					second.subscribe("/topic/market", marketHandler(secondMessages));

					JsonNode received = await()
							.atMost(5, TimeUnit.SECONDS)
							.until(() -> {
								publisher.publish("MARKET_TEST", Map.of("sequence", 1));

								return firstMessages.stream()
										.filter(secondMessages::contains)
										.findFirst()
										.orElse(null);
							}, Objects::nonNull);

					assertEquals("MARKET_TEST", received.get("type").asString());
					assertEquals(1, received.get("data").get("sequence").asInt());

					first.disconnect();

					publisher.publish("MARKET_TEST", Map.of("sequence", 2));

					await()
							.atMost(5, TimeUnit.SECONDS)
							.until(() -> secondMessages.stream().anyMatch(message ->
									message.get("data").get("sequence").asInt() == 2
							));

					assertTrue(second.isConnected());
				} finally {
					second.disconnect();
				}
			} finally {
				if (first.isConnected()) {
					first.disconnect();
				}
			}
		} finally {
			client.stop();
		}
	}

	
}	
