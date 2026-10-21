package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.wallet.InMemoryWallet;

/** Monta o módulo de ordens sem Spring, para testes rápidos e determinísticos. */
final class OrdersKit {

    final InMemoryWallet wallet;
    final OrderProcessor processor;
    final OrderService service;

    OrdersKit(String initialBalance, OrderNotifier notifier) {
        this.wallet = new InMemoryWallet(new BigDecimal(initialBalance));
        this.processor = new OrderProcessor(wallet, notifier);
        this.service = new OrderService(new OrderValidator(new AssetCatalog()), processor);
        this.processor.start();
    }

    OrdersKit(String initialBalance) {
        this(initialBalance, order -> { });
    }

    void close() {
        processor.shutdown();
    }

    static OrderRequest buy(String user, String clientId, String symbol, long qty, String price) {
        return new OrderRequest(user, clientId, symbol, OrderSide.BUY, qty, new BigDecimal(price));
    }

    static OrderRequest sell(String user, String clientId, String symbol, long qty, String price) {
        return new OrderRequest(user, clientId, symbol, OrderSide.SELL, qty, new BigDecimal(price));
    }

    /** Espera o processador decidir a ordem (ACCEPTED ou REJECTED). */
    Order awaitFinal(String orderId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Order order = service.findById(orderId).orElseThrow();
            if (order.status() != OrderStatus.RECEIVED) {
                return order;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Ordem não foi processada em 5s: " + orderId);
    }
}
