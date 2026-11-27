package br.ufs.simulador_bolsa.observability;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import br.ufs.simulador_bolsa.auth.service.SessionRegistry;
import br.ufs.simulador_bolsa.orders.OrderProcessor;
import br.ufs.simulador_bolsa.orders.OrderRecoveryRunner;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ObservabilityController {

    private final MarketMetricsService metricsService;
    private final OrderProcessor orderProcessor;
    private final SessionRegistry sessionRegistry;
    private final OrderRecoveryRunner recoveryRunner;

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        return ResponseEntity.ok(
                metricsService.snapshot(
                        orderProcessor.pendingCount(),
                        sessionRegistry.getActiveSessionCount()
                )
        );
    }

    @GetMapping("/system/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("timestamp", Instant.now().toString());
        status.put("queuePending", orderProcessor.pendingCount());
        status.put("activeStompSessions", sessionRegistry.getActiveSessionCount());
        return ResponseEntity.ok(status);
    }

    @PostMapping("/system/fault/simulate-crash-recovery")
    public ResponseEntity<Map<String, Object>> simulateCrashRecovery() {
        return ResponseEntity.ok(recoveryRunner.simulateCrashAndRecover());
    }
}
