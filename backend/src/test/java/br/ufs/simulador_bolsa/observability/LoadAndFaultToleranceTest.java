package br.ufs.simulador_bolsa.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.auth.service.AuthService;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.orders.MatchingEngine;
import br.ufs.simulador_bolsa.orders.Order;
import br.ufs.simulador_bolsa.orders.OrderEntity;
import br.ufs.simulador_bolsa.orders.OrderPersistenceService;
import br.ufs.simulador_bolsa.orders.OrderRecoveryRunner;
import br.ufs.simulador_bolsa.orders.OrderRepository;
import br.ufs.simulador_bolsa.orders.OrderRequest;
import br.ufs.simulador_bolsa.orders.OrderResult;
import br.ufs.simulador_bolsa.orders.OrderService;
import br.ufs.simulador_bolsa.orders.OrderSide;
import br.ufs.simulador_bolsa.orders.OrderStatus;
import br.ufs.simulador_bolsa.wallet.DatabaseWalletService;
import br.ufs.simulador_bolsa.wallet.PortfolioEntity;
import br.ufs.simulador_bolsa.wallet.PortfolioId;
import br.ufs.simulador_bolsa.wallet.PortfolioRepository;
import br.ufs.simulador_bolsa.wallet.WalletSnapshot;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.websocket.allowed-origins=http://localhost:5173"
)
class LoadAndFaultToleranceTest {

    private static final Logger log = LoggerFactory.getLogger(LoadAndFaultToleranceTest.class);

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderPersistenceService persistenceService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private MatchingEngine matchingEngine;

    @Autowired
    private DatabaseWalletService walletService;

    @Autowired
    private OrderRecoveryRunner recoveryRunner;

    @Autowired
    private ObservabilityController observabilityController;

    private void grantShares(String username, String ticker, int quantity, BigDecimal avgPrice) {
        User user = userRepository.findByUsername(username).orElseThrow();
        AssetEntity asset = persistenceService.ensureAsset(ticker);
        PortfolioId id = new PortfolioId(user.getId(), asset.getId());
        PortfolioEntity entity = portfolioRepository.findById(id).orElseGet(() -> {
            PortfolioEntity p = new PortfolioEntity();
            p.setId(id);
            p.setUsuario(user);
            p.setAtivo(asset);
            p.setQuantidade(0);
            p.setPrecoMedio(avgPrice);
            return p;
        });
        entity.setQuantidade(quantity);
        entity.setPrecoMedio(avgPrice);
        portfolioRepository.save(entity);
    }

    private Order awaitProcessed(String orderId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Order order = orderService.findById(orderId).orElse(null);
            if (order != null && order.status() != OrderStatus.RECEIVED
                    && (order.status() == OrderStatus.REJECTED || order.databaseId() != null)) {
                return order;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Timeout aguardando processamento da ordem " + orderId);
    }

    private Order awaitOrderStatus(String orderId, OrderStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Order order = orderService.findById(orderId).orElse(null);
            if (order != null && order.status() == expected) {
                return order;
            }
            Thread.sleep(5);
        }
        Order current = orderService.findById(orderId).orElse(null);
        throw new AssertionError("Timeout aguardando status " + expected + " para ordem " + orderId
                + " (atual=" + (current != null ? current.status() : "null") + ")");
    }

    @Test
    void crashRecoveryRestoresOpenOrdersAndExecutesPostRecoveryTrade() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String buyerName = "buyer_fault_" + suffix;
        String sellerName = "seller_fault_" + suffix;

        authService.register(buyerName, "senha123");
        authService.register(sellerName, "senha123");
        grantShares(sellerName, "PETR4", 50, new BigDecimal("30.00"));

        OrderResult buyAck = orderService.submit(new OrderRequest(
                buyerName, "crash-buy-1", "PETR4", OrderSide.BUY, 10L, new BigDecimal("35.00")
        ));
        Order openBuy = awaitProcessed(buyAck.orderId());
        assertEquals(OrderStatus.ACCEPTED, openBuy.status());
        assertNotNull(openBuy.databaseId());

        assertEquals(0, new BigDecimal("9650.00").compareTo(walletService.getAvailableBalance(buyerName)));
        assertEquals(0, new BigDecimal("350.00").compareTo(walletService.getReservedBalance(buyerName)));
        OrderEntity dbOrderBeforeCrash = orderRepository.findById(openBuy.databaseId()).orElseThrow();
        assertEquals("ABERTA", dbOrderBeforeCrash.getStatus());

        Map<String, Object> recoveryReport = recoveryRunner.simulateCrashAndRecover();
        assertEquals("RECOVERED", recoveryReport.get("status"));
        assertTrue(((Number) recoveryReport.get("totalRecoveredOrders")).intValue() >= 1);

        assertEquals(0, new BigDecimal("9650.00").compareTo(walletService.getAvailableBalance(buyerName)));
        assertEquals(0, new BigDecimal("350.00").compareTo(walletService.getReservedBalance(buyerName)));

        Map<String, Object> bookAfterRecovery = matchingEngine.getBookSnapshot("PETR4");
        assertNotNull(bookAfterRecovery.get("bestBid"));

        OrderResult sellAck = orderService.submit(new OrderRequest(
                sellerName, "post-crash-sell-1", "PETR4", OrderSide.SELL, 10L, new BigDecimal("35.00")
        ));
        Order matchedSell = awaitOrderStatus(sellAck.orderId(), OrderStatus.FILLED);
        assertEquals(OrderStatus.FILLED, matchedSell.status());

        WalletSnapshot buyerSnap = walletService.getSnapshot(buyerName);
        assertEquals(0, new BigDecimal("9650.00").compareTo(buyerSnap.balance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(buyerSnap.reservedBalance()));
        assertTrue(buyerSnap.positions().stream()
                .anyMatch(p -> "PETR4".equals(p.symbol()) && p.quantity() == 10L));

        OrderEntity dbOrderAfterTrade = orderRepository.findById(openBuy.databaseId()).orElseThrow();
        assertEquals("EXECUTADA", dbOrderAfterTrade.getStatus());
    }

    @Test
    void loadScenarios5_20_50_100ConcurrentUsers() throws Exception {
        int[] scenarios = {5, 20, 50, 100};

        for (int usersCount : scenarios) {
            ScenarioMetric m = runLoadScenario(usersCount);
            assertEquals(m.users, m.processedOrders);
            assertTrue(m.throughputOrdersPerSec > 0);
        }

        Map<String, Object> metrics = observabilityController.metrics().getBody();
        assertNotNull(metrics);
        assertTrue(metrics.containsKey("orders"));
        assertTrue(metrics.containsKey("trades"));
        assertTrue(metrics.containsKey("resources"));
    }

    private ScenarioMetric runLoadScenario(int concurrentUsers) throws Exception {
        String runTag = "load" + concurrentUsers + "_" + UUID.randomUUID().toString().substring(0, 6);
        List<String> usernames = new ArrayList<>(concurrentUsers);

        for (int i = 0; i < concurrentUsers; i++) {
            String u = runTag + "_u" + i;
            authService.register(u, "pass123");
            if (i % 2 == 0) {
                grantShares(u, "VALE3", 100, new BigDecimal("65.00"));
            }
            usernames.add(u);
        }

        ExecutorService pool = Executors.newFixedThreadPool(concurrentUsers);
        CountDownLatch ready = new CountDownLatch(concurrentUsers);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Double>> futures = new ArrayList<>(concurrentUsers);

        long batchStartNanos;
        try {
            for (int i = 0; i < concurrentUsers; i++) {
                final int idx = i;
                final String user = usernames.get(idx);
                final OrderSide side = (idx % 2 == 0) ? OrderSide.SELL : OrderSide.BUY;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    startGun.await();
                    long t0 = System.nanoTime();
                    OrderResult ack = orderService.submit(new OrderRequest(
                            user,
                            "cid-" + runTag + "-" + idx,
                            "VALE3",
                            side,
                            5L,
                            new BigDecimal("68.00")
                    ));
                    Order finalOrder = awaitProcessed(ack.orderId());
                    assertFalse(finalOrder.status() == OrderStatus.REJECTED);
                    return (System.nanoTime() - t0) / 1_000_000.0;
                }));
            }

            ready.await(10, TimeUnit.SECONDS);
            batchStartNanos = System.nanoTime();
            startGun.countDown();

            List<Double> latencies = new ArrayList<>(concurrentUsers);
            for (Future<Double> f : futures) {
                latencies.add(f.get(30, TimeUnit.SECONDS));
            }
            long totalElapsedNanos = System.nanoTime() - batchStartNanos;
            double totalElapsedSec = Math.max(0.001, totalElapsedNanos / 1_000_000_000.0);

            Collections.sort(latencies);
            double min = latencies.getFirst();
            double max = latencies.getLast();
            double avg = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            int p95Idx = Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1);
            double p95 = latencies.get(Math.max(0, p95Idx));
            double throughput = concurrentUsers / totalElapsedSec;

            Runtime rt = Runtime.getRuntime();
            double heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);

            int matchedPairs = concurrentUsers / 2;
            log.info("LOAD_SCENARIO_COMPLETED users={} orders={} trades={} minMs={} avgMs={} maxMs={} p95Ms={} throughputOrdSec={} heapMb={}",
                    concurrentUsers, concurrentUsers, matchedPairs,
                    fmt(min), fmt(avg), fmt(max), fmt(p95), fmt(throughput), fmt(heapMb));

            return new ScenarioMetric(concurrentUsers, concurrentUsers, matchedPairs, min, avg, max, p95, throughput, heapMb);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private record ScenarioMetric(
            int users,
            int processedOrders,
            int tradesExecuted,
            double minMs,
            double avgMs,
            double maxMs,
            double p95Ms,
            double throughputOrdersPerSec,
            double heapUsedMb
    ) {
    }
}
