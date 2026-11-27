package br.ufs.simulador_bolsa.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import br.ufs.simulador_bolsa.communication.MarketEventPublisher;
import br.ufs.simulador_bolsa.market.Asset;
import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.orders.OrderRequest;
import br.ufs.simulador_bolsa.orders.OrderResult;
import br.ufs.simulador_bolsa.orders.OrderService;
import br.ufs.simulador_bolsa.orders.OrderSide;
import br.ufs.simulador_bolsa.orders.OrderStatus;
import br.ufs.simulador_bolsa.wallet.WalletPort;

@Slf4j
@RequiredArgsConstructor
@Configuration(proxyBeanMethods = false)
@Profile("simulation")
@EnableScheduling
class MarketSimulation {

    private static final Map<String, Long> QUANTITIES = Map.of("npc_conservador", 1L, "npc_ativo", 5L);

    private final NpcAccounts accounts;
    private final AssetCatalog catalog;
    private final AssetRepository assets;
    private final WalletPort wallet;
    private final OrderService orders;
    private final MarketEventPublisher publisher;
    private final Map<String, String> openOrders = new HashMap<>();
    private volatile EconomicEvent currentEvent;

    @Scheduled(initialDelayString = "${market.simulation.event-delay-ms:600000}",
            fixedDelayString = "${market.simulation.event-delay-ms:600000}")
    void generateEvent() {
        List<Asset> availableAssets = catalog.listAll();
        if (availableAssets.isEmpty()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String symbol = availableAssets.get(random.nextInt(availableAssets.size())).symbol();
        EconomicEvent event = random.nextBoolean()
                ? new EconomicEvent("Perspectiva de alta", "A demanda por " + symbol + " aumentou.", symbol, 5)
                : new EconomicEvent("Perspectiva de queda", "A demanda por " + symbol + " caiu.", symbol, -5);
        applyEvent(event);
    }

    void applyEvent(EconomicEvent event) {
        currentEvent = event;
        log.info("ECONOMIC_EVENT_APPLIED symbol={} impactPercent={}", event.symbol(), event.impactPercent());
        try {
            publisher.publish("ECONOMIC_EVENT", event);
        } catch (RuntimeException e) {
            log.warn("ECONOMIC_EVENT_NOTIFY_FAILED symbol={}", event.symbol(), e);
        }
    }

    @Scheduled(initialDelayString = "${market.simulation.order-delay-ms:5000}",
            fixedDelayString = "${market.simulation.order-delay-ms:5000}")
    void submitOrders() {
        EconomicEvent event = currentEvent;
        List<Asset> availableAssets = catalog.listAll();
        QUANTITIES.forEach((username, quantity) -> {
            try {
                String userId = accounts.ensureAccount(username);
                for (Asset asset : availableAssets) {
                    submitOrder(userId, quantity, asset, event);
                }
            } catch (RuntimeException e) {
                log.warn("NPC_CYCLE_FAILED username={}", username, e);
            }
        });
    }

    private void submitOrder(String userId, long quantity, Asset asset, EconomicEvent event) {
        String symbol = asset.symbol();
        String key = userId + "|" + symbol;
        String activeOrderId = openOrders.get(key);
        if (activeOrderId != null && orders.findById(activeOrderId).filter(order -> isOpen(order.status())).isPresent()) {
            return;
        }
        openOrders.remove(key);

        long availableShares = wallet.getAvailableShares(userId, symbol);
        int impact = event != null && symbol.equals(event.symbol()) ? event.impactPercent() : 0;
        OrderSide side = impact < 0 || (impact == 0 && availableShares > 0) ? OrderSide.SELL : OrderSide.BUY;
        if (side == OrderSide.SELL) {
            quantity = Math.min(quantity, availableShares);
            if (quantity == 0) {
                return;
            }
        }

        // A notícia altera o limite do NPC. A cotação oficial vem da negociação.
        BigDecimal marketPrice = assets.findByTicker(symbol).map(AssetEntity::getPrecoAtual).orElse(asset.currentPrice());
        BigDecimal price = marketPrice.multiply(BigDecimal.valueOf(100L + impact, 2))
                .setScale(2, RoundingMode.HALF_UP);
        if (side == OrderSide.BUY && wallet.getAvailableBalance(userId).compareTo(price.multiply(BigDecimal.valueOf(quantity))) < 0) {
            return;
        }

        OrderRequest request = new OrderRequest(userId, UUID.randomUUID().toString(), symbol, side, quantity, price);
        OrderResult result = orders.submit(request);
        if (isOpen(result.status())) {
            openOrders.put(key, result.orderId());
        }
        log.info("NPC_ORDER_SUBMITTED userId={} clientOrderId={} orderId={} symbol={} side={} quantity={} price={} status={} reason={}",
                userId, request.clientOrderId(), result.orderId(), symbol, side, quantity, price, result.status(), result.reason());
    }

    private boolean isOpen(OrderStatus status) {
        return status == OrderStatus.RECEIVED || status == OrderStatus.ACCEPTED || status == OrderStatus.PARTIALLY_FILLED;
    }
}
