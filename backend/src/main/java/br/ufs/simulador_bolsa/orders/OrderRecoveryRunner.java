package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import br.ufs.simulador_bolsa.observability.MarketMetricsService;
import br.ufs.simulador_bolsa.wallet.DatabaseWalletService;
import lombok.RequiredArgsConstructor;

/**
 * Componente de tolerância a falhas e recuperação pós-falha (Crash Recovery).
 * Ao iniciar ou reiniciar o backend, garante o catálogo de ativos no banco e busca todas as ordens
 * com status 'ABERTA' persistidas no PostgreSQL para restabelecer o livro de ofertas e as reservas
 * de carteira sem perda de consistência.
 */
@Component
@RequiredArgsConstructor
public class OrderRecoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryRunner.class);

    private final OrderRepository orderRepository;
    private final OrderPersistenceService persistenceService;
    private final OrderService orderService;
    private final MatchingEngine matchingEngine;
    private final DatabaseWalletService walletService;
    private final MarketMetricsService metricsService;

    @EventListener(ApplicationReadyEvent.class)
    public List<Map<String, Object>> recoverOpenOrders() {
        persistenceService.seedDefaultAssetsIfNeeded();

        log.info("CRASH_RECOVERY_STARTED buscando ordens abertas no banco de dados...");
        List<OrderEntity> openOrders = orderRepository.findByStatusWithAssetAndUser("ABERTA");
        log.info("CRASH_RECOVERY_FOUND totalOpenOrders={}", openOrders.size());

        List<Map<String, Object>> recoveredDetails = new ArrayList<>();

        for (OrderEntity entity : openOrders) {
            String username = entity.getUsuario().getUsername();
            String ticker = entity.getAtivo().getTicker();
            OrderSide side = "VENDA".equalsIgnoreCase(entity.getTipo()) ? OrderSide.SELL : OrderSide.BUY;
            long qty = entity.getQuantidade();
            BigDecimal price = entity.getPrecoAlvo();

            log.info("ORDER_RECOVERED id={} user={} asset={} side={} qty={} targetPrice={}",
                    entity.getId(),
                    username,
                    ticker,
                    entity.getTipo(),
                    qty,
                    price);

            // Restaura a reserva em memória correspondente à ordem aberta no banco
            boolean reserved = side == OrderSide.BUY
                    ? walletService.reserveFunds(username, price.multiply(BigDecimal.valueOf(qty)))
                    : walletService.reserveShares(username, ticker, qty);

            if (!reserved) {
                log.warn("ORDER_RECOVERY_RESERVE_SKIPPED id={} user={} asset={}", entity.getId(), username, ticker);
                continue;
            }

            String clientOrderId = "recovered-" + entity.getId();
            OrderRequest req = new OrderRequest(username, clientOrderId, ticker, side, qty, price);
            Order recoveredOrder = new Order(
                    "db-" + entity.getId(),
                    req,
                    orderService.nextSequenceNumber(),
                    OrderStatus.ACCEPTED,
                    entity.getId()
            );

            orderService.registerRecoveredOrder(recoveredOrder);
            matchingEngine.restoreOrder(recoveredOrder);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dbId", entity.getId());
            item.put("orderId", recoveredOrder.orderId());
            item.put("user", username);
            item.put("asset", ticker);
            item.put("side", entity.getTipo());
            item.put("quantity", qty);
            item.put("targetPrice", price);
            recoveredDetails.add(item);
        }

        metricsService.recordOrdersRecovered(recoveredDetails.size());
        log.info("CRASH_RECOVERY_COMPLETED estado de ordens abertas restabelecido com sucesso. recuperadas={}",
                recoveredDetails.size());
        return recoveredDetails;
    }

    /**
     * Simula uma queda abrupta do servidor (limpando todo o estado volátil da memória:
     * livro de ofertas, mapa de ordens do OrderService e reservas em memória da carteira)
     * seguida imediatamente pela rotina de Crash Recovery a partir do banco de dados PostgreSQL.
     */
    public synchronized Map<String, Object> simulateCrashAndRecover() {
        log.warn("FAULT_INJECTION_CRASH_SIMULATED limpando estado em memória (OrderBook, OrderService e Reservas)...");
        matchingEngine.clearBooks();
        orderService.clearInMemoryOrders();
        walletService.clearInMemoryReservations();

        List<Map<String, Object>> recovered = recoverOpenOrders();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "RECOVERED");
        report.put("totalRecoveredOrders", recovered.size());
        report.put("recoveredOrders", recovered);
        return report;
    }
}
