package br.ufs.simulador_bolsa.wallet;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import br.ufs.simulador_bolsa.orders.OrderEntity;
import br.ufs.simulador_bolsa.orders.OrderRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/** Reconcilia reservas em memória após falha na liberação pós-commit, sem alterar o motor. */
@Component
@RequiredArgsConstructor
public class ReservationReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReservationReconciler.class);

    private static final long[] RETRY_DELAYS_MS = {0L, 100L, 500L};
    private static final int MAX_ATTEMPTS = RETRY_DELAYS_MS.length;
    private static final String OPEN_STATUS = "ABERTA";

    private final DatabaseWalletService wallet;
    private final OrderRepository orderRepository;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "reservation-reconciler");
        thread.setDaemon(true);
        return thread;
    });

    public void enqueue(String kind, String username, Runnable release) {
        schedule(new ReleaseTask(kind, username, release, 1), 0L);
    }

    private void schedule(ReleaseTask task, long delayMs) {
        try {
            scheduler.schedule(() -> execute(task), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.error("RESERVATION_RETRY_REJECTED kind={} user={}", task.kind(), task.username(), e);
            reconcile(task.username());
        }
    }

    private void execute(ReleaseTask task) {
        try {
            task.release().run();
            log.info("RESERVATION_RELEASE_RECOVERED kind={} user={} attempt={}",
                    task.kind(), task.username(), task.attempt());
        } catch (RuntimeException e) {
            log.warn("RESERVATION_RELEASE_RETRY kind={} user={} attempt={} error={}",
                    task.kind(), task.username(), task.attempt(), e.toString());

            if (task.attempt() < MAX_ATTEMPTS) {
                schedule(task.next(), RETRY_DELAYS_MS[task.attempt()]);
            } else {
                reconcile(task.username());
            }
        }
    }

    /** A quantidade persistida é o saldo não executado após o commit. */
    private void reconcile(String username) {
        if (username == null || username.isBlank()) {
            return;
        }

        BigDecimal funds = BigDecimal.ZERO;
        Map<String, Long> shares = new HashMap<>();

        try {
            for (OrderEntity order : orderRepository.findOpenOrdersByUsername(username, OPEN_STATUS)) {
                long remaining = order.getQuantidade() == null ? 0L : order.getQuantidade().longValue();
                if (remaining <= 0 || order.getAtivo() == null || order.getAtivo().getTicker() == null) {
                    continue;
                }

                if ("VENDA".equalsIgnoreCase(order.getTipo())) {
                    String ticker = order.getAtivo().getTicker().trim().toUpperCase(Locale.ROOT);
                    shares.merge(ticker, remaining, Long::sum);
                } else {
                    BigDecimal targetPrice = order.getPrecoAlvo() != null ? order.getPrecoAlvo() : BigDecimal.ZERO;
                    funds = funds.add(targetPrice.multiply(BigDecimal.valueOf(remaining)));
                }
            }

            wallet.replaceReservations(username, funds, shares);
            log.warn("RESERVATIONS_RECONCILED user={} funds={} symbols={}", username, funds, shares.keySet());
        } catch (RuntimeException e) {
            // Nunca propaga para a thread do agendador: o commit já aconteceu.
            log.error("RESERVATION_RECONCILE_FAILED user={}", username, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private record ReleaseTask(String kind, String username, Runnable release, int attempt) {

        private ReleaseTask next() {
            return new ReleaseTask(kind, username, release, attempt + 1);
        }
    }
}
