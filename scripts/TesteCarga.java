import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TesteCarga {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final int[] CENARIOS = {5, 20, 50, 100};

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8086";
        String wsUrl = baseUrl.replaceFirst("^http", "ws") + "/ws";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        for (int usuarios : CENARIOS) {
            rodarCenario(client, baseUrl, wsUrl, usuarios);
        }

        HttpRequest metricsReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/metrics"))
                .GET()
                .build();
        HttpResponse<String> metricsResp = client.send(metricsReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("\nMetricas (/metrics):");
        System.out.println(metricsResp.body());
    }

    private static void rodarCenario(HttpClient client, String baseUrl, String wsUrl, int usuarios) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        List<String> tokens = new ArrayList<>(usuarios);

        for (int i = 0; i < usuarios; i++) {
            String user = "carga_" + tag + "_" + i;
            String body = "{\"username\":\"" + user + "\",\"password\":\"senha123\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/auth/register"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            Matcher m = TOKEN_PATTERN.matcher(resp.body());
            if (!m.find()) {
                throw new IllegalStateException("Falha ao registrar " + user + ": " + resp.body());
            }
            tokens.add(m.group(1));
        }

        ExecutorService pool = Executors.newFixedThreadPool(usuarios);
        CountDownLatch prontos = new CountDownLatch(usuarios);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Double>> futuros = new ArrayList<>(usuarios);

        try {
            for (int i = 0; i < usuarios; i++) {
                final int idx = i;
                final String token = tokens.get(idx);
                futuros.add(pool.submit(() -> {
                    prontos.countDown();
                    largada.await();
                    return enviarOrdemStomp(client, wsUrl, token, "cid-" + tag + "-" + idx);
                }));
            }

            prontos.await(10, TimeUnit.SECONDS);
            long inicio = System.nanoTime();
            largada.countDown();

            List<Double> latencias = new ArrayList<>(usuarios);
            for (Future<Double> f : futuros) {
                latencias.add(f.get(30, TimeUnit.SECONDS));
            }
            double tempoTotalSeg = Math.max(0.001, (System.nanoTime() - inicio) / 1_000_000_000.0);

            Collections.sort(latencias);
            double min = latencias.getFirst();
            double max = latencias.getLast();
            double soma = 0.0;
            for (double v : latencias) {
                soma += v;
            }
            double media = soma / latencias.size();
            int p95Idx = Math.min(latencias.size() - 1, Math.max(0, (int) Math.ceil(latencias.size() * 0.95) - 1));
            double p95 = latencias.get(p95Idx);
            double vazao = usuarios / tempoTotalSeg;

            System.out.printf(Locale.ROOT,
                    "usuarios=%d min=%.2fms med=%.2fms p95=%.2fms max=%.2fms vazao=%.2fordens/s%n",
                    usuarios, min, media, p95, max, vazao);
        } finally {
            pool.shutdownNow();
        }
    }

    private static double enviarOrdemStomp(HttpClient client, String wsUrl, String token, String clientOrderId) throws Exception {
        CountDownLatch conectado = new CountDownLatch(1);
        CountDownLatch respondido = new CountDownLatch(1);

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder acumulador = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                acumulador.append(data);
                if (last) {
                    String msg = acumulador.toString();
                    acumulador.setLength(0);
                    if (msg.startsWith("CONNECTED")) {
                        conectado.countDown();
                    }
                    if (msg.contains("ORDER_ACK") || msg.contains("ORDER_UPDATED")) {
                        respondido.countDown();
                    }
                }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }
        };

        long t0 = System.nanoTime();
        WebSocket ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost:5173")
                .buildAsync(URI.create(wsUrl), listener)
                .get(10, TimeUnit.SECONDS);

        try {
            String connectFrame = "CONNECT\naccept-version:1.2\nAuthorization:Bearer " + token + "\n\n\0";
            ws.sendText(connectFrame, true).get(5, TimeUnit.SECONDS);
            conectado.await(5, TimeUnit.SECONDS);

            String subFrame = "SUBSCRIBE\nid:s1\ndestination:/user/queue/orders\n\n\0";
            ws.sendText(subFrame, true).get(5, TimeUnit.SECONDS);

            String payload = "{\"clientOrderId\":\"" + clientOrderId
                    + "\",\"symbol\":\"PETR4\",\"side\":\"BUY\",\"quantity\":1,\"price\":\"35.00\"}";
            String sendFrame = "SEND\ndestination:/app/orders/create\ncontent-type:application/json\n\n" + payload + "\0";
            ws.sendText(sendFrame, true).get(5, TimeUnit.SECONDS);

            respondido.await(10, TimeUnit.SECONDS);
            return (System.nanoTime() - t0) / 1_000_000.0;
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").exceptionally(ex -> null);
        }
    }
}
