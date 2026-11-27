package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.ufs.simulador_bolsa.communication.MarketEventPublisher;
import br.ufs.simulador_bolsa.wallet.WalletPort;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngine {

    private final SettlementPort settlement;
    private final WalletPort wallet;
    private final OrderNotifier notifier;
    private final Map<String, OrderBook> books = new HashMap<>();

    @Autowired(required = false)
    private OrderPersistenceService persistenceService;

    @Autowired(required = false)
    private MarketEventPublisher eventPublisher;

    public synchronized void match(Order incoming) {
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
                publishBookUpdate(incoming.symbol(), book);
                return;
            }

            long qty = Math.min(incoming.remainingQuantity(), resting.remainingQuantity());
            Order buyOrder = buying ? incoming : resting;
            Order sellOrder = buying ? resting : incoming;

            log.info("MATCH_FOUND symbol={} buyOrderId={} sellOrderId={} qty={} price={}",
                    incoming.symbol(), buyOrder.orderId(), sellOrder.orderId(), qty, resting.price());

            Trade trade = new Trade(buyOrder, sellOrder, qty, resting.price());
            try {
                settlement.settle(trade);
            } catch (RuntimeException e) {
                log.error("SETTLEMENT_FAILED orderId={}", incoming.orderId(), e);
                cancelRemainder(incoming, RejectReason.INTERNAL_ERROR);
                publishBookUpdate(incoming.symbol(), book);
                return;
            }

            incoming.fill(qty);
            resting.fill(qty);
            if (resting.remainingQuantity() == 0) book.remove(resting);
            notifyQuietly(resting);
        }

        if (incoming.remainingQuantity() > 0) {
            book.add(incoming);
            log.info("ORDER_BOOK_INSERTED orderId={} userId={} symbol={} side={} remainingQty={} price={}",
                    incoming.orderId(), incoming.userId(), incoming.symbol(),
                    incoming.side(), incoming.remainingQuantity(), incoming.price());
        }
        publishBookUpdate(incoming.symbol(), book);
    }

    public synchronized void restoreOrder(Order recovered) {
        if (recovered == null || recovered.remainingQuantity() <= 0) return;
        OrderBook book = books.computeIfAbsent(recovered.symbol(), s -> new OrderBook());
        book.add(recovered);
        log.info("ORDER_BOOK_RESTORED orderId={} dbId={} symbol={} side={} qty={} price={}",
                recovered.orderId(), recovered.databaseId(), recovered.symbol(),
                recovered.side(), recovered.remainingQuantity(), recovered.price());
        publishBookUpdate(recovered.symbol(), book);
    }

    public synchronized boolean cancelFromBook(Order order) {
        if (order == null) return false;

        if (order.status() != OrderStatus.ACCEPTED
                && order.status() != OrderStatus.PARTIALLY_FILLED) {
            return false;
        }

        OrderBook book = books.get(order.symbol());

        if (book == null || !book.contains(order)) {
            return false;
        }

        if (persistenceService != null) {
            persistenceService.markOrderCancelled(order);
        }

        long remaining = order.remainingQuantity();

        if (!order.cancel()) {
            return false;
        }

        book.remove(order);

        releaseReservedResources(order, remaining);

        publishBookUpdate(order.symbol(), book);

        return true;
    }

    public synchronized Map<String, Object> getBookSnapshot(String symbol) {
        String sym = symbol != null ? symbol.trim().toUpperCase() : "PETR4";
        OrderBook book = books.computeIfAbsent(sym, s -> new OrderBook());
        return book.toSnapshot(sym);
    }

    public synchronized void clearBooks() {
        books.clear();
    }

    private void cancelRemainder(Order order, RejectReason reason) {
        if (order.status() != OrderStatus.ACCEPTED
                && order.status() != OrderStatus.PARTIALLY_FILLED) {
            return;
        }

        if (persistenceService != null) {
            persistenceService.markOrderCancelled(order);
        }

        long remaining = order.remainingQuantity();
        if (order.cancel(reason)) {
            releaseReservedResources(order, remaining);
        }
    }

    private void releaseReservedResources(Order order, long remaining) {
        if (remaining <= 0) return;
        if (order.side() == OrderSide.BUY) {
            wallet.releaseFunds(order.userId(),
                    order.price().multiply(BigDecimal.valueOf(remaining)));
        } else {
            wallet.releaseShares(order.userId(), order.symbol(), remaining);
        }
    }

    private void publishBookUpdate(String symbol, OrderBook book) {
        if (eventPublisher == null || book == null) return;
        try {
            eventPublisher.publish("ORDER_BOOK_UPDATED", book.toSnapshot(symbol));
        } catch (RuntimeException e) {
            log.debug("BOOK_BROADCAST_SKIPPED symbol={}", symbol);
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
