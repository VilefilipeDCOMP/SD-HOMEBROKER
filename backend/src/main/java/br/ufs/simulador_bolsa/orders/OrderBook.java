package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.util.*;

//não é thread-safe: só a thread do OrderProcessor pode acessar
public class OrderBook {

    private final NavigableMap<BigDecimal, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<BigDecimal, Deque<Order>> asks = new TreeMap<>();

    public void add(Order order) {
        sideOf(order.side())
                .computeIfAbsent(order.price(), p -> new ArrayDeque<>())
                .addLast(order);
    }

    public Optional<Order> best(OrderSide side) {
        var entry = sideOf(side).firstEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getValue().getFirst());
    }

    public boolean remove(Order order) {
        var levels = sideOf(order.side());
        var queue = levels.get(order.price());
        if (queue == null || !queue.remove(order)) {
            return false;
        }
        if (queue.isEmpty()) {
            levels.remove(order.price());
        }
        return true;
    }

    private NavigableMap<BigDecimal, Deque<Order>> sideOf(OrderSide side) {
        return side == OrderSide.BUY ? bids : asks;
    }

    public Map<String, Object> toSnapshot(String symbol) {
        List<Map<String, Object>> bidLevels = aggregateLevels(bids);
        List<Map<String, Object>> askLevels = aggregateLevels(asks);
        BigDecimal bestBid = bids.isEmpty() ? null : bids.firstKey();
        BigDecimal bestAsk = asks.isEmpty() ? null : asks.firstKey();

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("symbol", symbol);
        snap.put("bids", bidLevels);
        snap.put("asks", askLevels);
        snap.put("bestBid", bestBid);
        snap.put("bestAsk", bestAsk);
        return snap;
    }

    private List<Map<String, Object>> aggregateLevels(NavigableMap<BigDecimal, Deque<Order>> sideMap) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : sideMap.entrySet()) {
            long totalQty = 0;
            for (Order o : entry.getValue()) {
                totalQty += o.remainingQuantity();
            }
            if (totalQty > 0) {
                Map<String, Object> level = new LinkedHashMap<>();
                level.put("price", entry.getKey());
                level.put("quantity", totalQty);
                result.add(level);
            }
        }
        return result;
    }
}