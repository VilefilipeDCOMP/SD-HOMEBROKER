package br.ufs.simulador_bolsa.observability;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

@Service
public class MarketMetricsService {

    private static final int MAX_LATENCY_SAMPLES = 20_000;

    private final Instant startedAt = Instant.now();

    private final LongAdder ordersReceived = new LongAdder();
    private final LongAdder ordersValidated = new LongAdder();
    private final LongAdder ordersAccepted = new LongAdder();
    private final LongAdder ordersRejected = new LongAdder();
    private final LongAdder ordersCancelled = new LongAdder();
    private final LongAdder ordersDuplicate = new LongAdder();
    private final LongAdder ordersRecovered = new LongAdder();

    private final LongAdder tradesExecuted = new LongAdder();
    private final LongAdder tradedSharesVolume = new LongAdder();
    private final AtomicLong tradedFinancialCents = new AtomicLong(0);

    private final ConcurrentLinkedDeque<Double> orderLatenciesMs = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Double> tradeLatenciesMs = new ConcurrentLinkedDeque<>();

    public void recordOrderReceived() {
        ordersReceived.increment();
    }

    public void recordOrderValidated() {
        ordersValidated.increment();
    }

    public void recordOrderAccepted(double latencyMs) {
        ordersAccepted.increment();
        addSample(orderLatenciesMs, latencyMs);
    }

    public void recordOrderRejected(double latencyMs) {
        ordersRejected.increment();
        if (latencyMs >= 0) {
            addSample(orderLatenciesMs, latencyMs);
        }
    }

    public void recordOrderCancelled() {
        ordersCancelled.increment();
    }

    public void recordOrderDuplicate() {
        ordersDuplicate.increment();
    }

    public void recordOrdersRecovered(int count) {
        if (count > 0) {
            ordersRecovered.add(count);
        }
    }

    public void recordTradeExecuted(long quantity, BigDecimal price, double latencyMs) {
        tradesExecuted.increment();
        tradedSharesVolume.add(quantity);
        if (price != null) {
            long cents = price.multiply(BigDecimal.valueOf(quantity))
                    .multiply(BigDecimal.valueOf(100))
                    .longValue();
            tradedFinancialCents.addAndGet(cents);
        }
        addSample(tradeLatenciesMs, latencyMs);
    }

    private void addSample(ConcurrentLinkedDeque<Double> deque, double valueMs) {
        if (valueMs < 0) return;
        deque.addLast(valueMs);
        while (deque.size() > MAX_LATENCY_SAMPLES) {
            deque.pollFirst();
        }
    }

    public LatencySummary getOrderLatencySummary() {
        return summarize(orderLatenciesMs);
    }

    public LatencySummary getTradeLatencySummary() {
        return summarize(tradeLatenciesMs);
    }

    private LatencySummary summarize(ConcurrentLinkedDeque<Double> deque) {
        List<Double> copy = new ArrayList<>(deque);
        if (copy.isEmpty()) {
            return new LatencySummary(0, 0.0, 0.0, 0.0, 0.0);
        }
        Collections.sort(copy);
        int n = copy.size();
        double min = copy.getFirst();
        double max = copy.getLast();
        double sum = 0.0;
        for (double v : copy) {
            sum += v;
        }
        double avg = sum / n;
        int p95Index = Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1);
        double p95 = copy.get(Math.max(0, p95Index));
        return new LatencySummary(
                n,
                round3(min),
                round3(avg),
                round3(max),
                round3(p95)
        );
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    public Map<String, Object> snapshot(int queuePendingCount, int activeSessions) {
        Map<String, Object> out = new LinkedHashMap<>();
        long uptimeMs = Math.max(1L, Instant.now().toEpochMilli() - startedAt.toEpochMilli());

        out.put("timestamp", Instant.now().toString());
        out.put("uptimeMs", uptimeMs);

        Map<String, Object> orders = new LinkedHashMap<>();
        orders.put("received", ordersReceived.sum());
        orders.put("validated", ordersValidated.sum());
        orders.put("accepted", ordersAccepted.sum());
        orders.put("rejected", ordersRejected.sum());
        orders.put("cancelled", ordersCancelled.sum());
        orders.put("duplicates", ordersDuplicate.sum());
        orders.put("recoveredFromCrash", ordersRecovered.sum());
        orders.put("queuePending", queuePendingCount);
        orders.put("latencyMs", getOrderLatencySummary());
        out.put("orders", orders);

        Map<String, Object> trades = new LinkedHashMap<>();
        trades.put("executed", tradesExecuted.sum());
        trades.put("volumeShares", tradedSharesVolume.sum());
        trades.put("financialVolume", BigDecimal.valueOf(tradedFinancialCents.get(), 2));
        trades.put("latencyMs", getTradeLatencySummary());
        out.put("trades", trades);

        Map<String, Object> sessions = new LinkedHashMap<>();
        sessions.put("activeStompSessions", activeSessions);
        out.put("sessions", sessions);

        Runtime rt = Runtime.getRuntime();
        long usedBytes = rt.totalMemory() - rt.freeMemory();
        long maxBytes = rt.maxMemory();
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsedMb", round3(usedBytes / (1024.0 * 1024.0)));
        jvm.put("heapMaxMb", round3(maxBytes / (1024.0 * 1024.0)));
        jvm.put("availableProcessors", rt.availableProcessors());
        jvm.put("activeThreads", Thread.activeCount());
        jvm.put("processCpuTimeNs", getProcessCpuTimeNs());
        out.put("resources", jvm);

        return out;
    }

    private long getProcessCpuTimeNs() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            return sunBean.getProcessCpuTime();
        }
        return -1L;
    }

    public void reset() {
        ordersReceived.reset();
        ordersValidated.reset();
        ordersAccepted.reset();
        ordersRejected.reset();
        ordersCancelled.reset();
        ordersDuplicate.reset();
        ordersRecovered.reset();
        tradesExecuted.reset();
        tradedSharesVolume.reset();
        tradedFinancialCents.set(0);
        orderLatenciesMs.clear();
        tradeLatenciesMs.clear();
    }

    public record LatencySummary(
            int samples,
            double minMs,
            double avgMs,
            double maxMs,
            double p95Ms
    ) {
    }
}
