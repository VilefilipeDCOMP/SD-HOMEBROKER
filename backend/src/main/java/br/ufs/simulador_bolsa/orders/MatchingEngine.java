package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import br.ufs.simulador_bolsa.wallet.WalletPort;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngine {

    private final SettlementPort settlement;
    private final WalletPort wallet;
    private final OrderNotifier notifier;
    private final Map<String, OrderBook> books = new HashMap<>();

    public void match(Order incoming) {
        OrderBook book = books.computeIfAbsent(incoming.symbol(), s -> new OrderBook());
        boolean buying = incoming.side() == OrderSide.BUY;
        OrderSide opposite = buying ? OrderSide.SELL : OrderSide.BUY;

        while (incoming.remainingQuantity() > 0) {
            Order resting = book.best(opposite).orElse(null);
            if (resting == null) break;

            int cmp = incoming.price().compareTo(resting.price());
            if (buying ? cmp < 0 : cmp > 0) break;

            if (resting.userId().equals(incoming.userId())) {
                cancelRemainder(incoming, RejectReason.SELF_TRADE);
                return;
            }

            long qty = Math.min(incoming.remainingQuantity(), resting.remainingQuantity());
            Trade trade = new Trade(buying ? incoming : resting, buying ?
                    resting : incoming, qty, resting.price());
            try {
                settlement.settle(trade);
            } catch (RuntimeException e) {
                log.error("SETTLEMENT_FAILED orderId={}", incoming.orderId(), e);
                cancelRemainder(incoming, RejectReason.INTERNAL_ERROR);
                return;
            }

            incoming.fill(qty);
            resting.fill(qty);
            if (resting.remainingQuantity() == 0) book.remove(resting);
            notifyQuietly(resting);
        }

        if (incoming.remainingQuantity() > 0) book.add(incoming);
    }

    private void cancelRemainder(Order order, RejectReason reason) {
        long remaining = order.remainingQuantity();
        if (!order.cancel(reason)) return;
        if (order.side() == OrderSide.BUY) {
            wallet.releaseFunds(order.userId(),
                    order.price().multiply(BigDecimal.valueOf(remaining)));
        } else {
            wallet.releaseShares(order.userId(), order.symbol(), remaining);
        }
    }

    private void notifyQuietly(Order order) {
        try {
            notifier.orderUpdated(order);
        } catch (RuntimeException e) {
            log.warn("ORDER_NOTIFY_FAILED orderId={}", order.orderId(), e);
        }
    }
}