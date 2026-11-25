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
}