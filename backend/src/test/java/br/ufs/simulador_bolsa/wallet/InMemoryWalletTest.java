package br.ufs.simulador_bolsa.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class InMemoryWalletTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "esperado " + expected + " mas foi " + actual);
    }

    @Test
    void newAccountStartsWithInitialBalance() {
        var wallet = new InMemoryWallet(new BigDecimal("10000.00"));
        assertMoney("10000.00", wallet.getAvailableBalance("u1"));
        assertEquals(0L, wallet.getAvailableShares("u1", "PETR4"));
    }

    @Test
    void reserveFailsWhenBalanceIsInsufficientAndChangesNothing() {
        var wallet = new InMemoryWallet(new BigDecimal("50.00"));
        assertFalse(wallet.reserveFunds("u1", HUNDRED));
        assertMoney("50.00", wallet.getAvailableBalance("u1"));
        assertMoney("0", wallet.getReservedBalance("u1"));
    }

    @Test
    void releaseReturnsReservedFunds() {
        var wallet = new InMemoryWallet(new BigDecimal("500.00"));
        assertTrue(wallet.reserveFunds("u1", HUNDRED));
        wallet.releaseFunds("u1", HUNDRED);
        assertMoney("500.00", wallet.getAvailableBalance("u1"));
        assertMoney("0", wallet.getReservedBalance("u1"));
    }

    @Test
    void releasingMoreThanReservedFails() {
        var wallet = new InMemoryWallet(new BigDecimal("500.00"));
        assertThrows(IllegalStateException.class, () -> wallet.releaseFunds("u1", HUNDRED));
    }

    @Test
    void sharesCanBeReservedAndReleased() {
        var wallet = new InMemoryWallet(BigDecimal.ZERO);
        wallet.depositShares("u1", "PETR4", 10);
        assertTrue(wallet.reserveShares("u1", "PETR4", 10));
        assertFalse(wallet.reserveShares("u1", "PETR4", 1));
        wallet.releaseShares("u1", "PETR4", 10);
        assertEquals(10L, wallet.getAvailableShares("u1", "PETR4"));
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        var wallet = new InMemoryWallet(HUNDRED);
        assertThrows(IllegalArgumentException.class, () -> wallet.reserveFunds("u1", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> wallet.reserveFunds("u1", new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> wallet.reserveShares("u1", "PETR4", 0));
    }

    @Test
    void accountsAreIndependent() {
        var wallet = new InMemoryWallet(HUNDRED);
        assertTrue(wallet.reserveFunds("u1", HUNDRED));
        assertMoney("100.00", wallet.getAvailableBalance("u2"));
    }

    @Test
    void concurrentReservesNeverExceedBalance() throws Exception {
        var wallet = new InMemoryWallet(new BigDecimal("1000.00"));
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return wallet.reserveFunds("u1", HUNDRED);
                }));
            }
            ready.await();
            go.countDown();

            int successes = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertEquals(10, successes);
            assertMoney("0", wallet.getAvailableBalance("u1"));
            assertMoney("1000.00", wallet.getReservedBalance("u1"));
        } finally {
            pool.shutdownNow();
        }
    }
}
