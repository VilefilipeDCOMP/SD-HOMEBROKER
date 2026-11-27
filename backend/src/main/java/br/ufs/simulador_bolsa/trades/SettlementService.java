package br.ufs.simulador_bolsa.trades;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.communication.MarketEvent;
import br.ufs.simulador_bolsa.communication.MarketEventPublisher;
import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.observability.MarketMetricsService;
import br.ufs.simulador_bolsa.orders.OrderEntity;
import br.ufs.simulador_bolsa.orders.OrderPersistenceService;
import br.ufs.simulador_bolsa.orders.OrderRepository;
import br.ufs.simulador_bolsa.orders.SettlementPort;
import br.ufs.simulador_bolsa.orders.Trade;
import br.ufs.simulador_bolsa.wallet.DatabaseWalletService;
import br.ufs.simulador_bolsa.wallet.PortfolioEntity;
import br.ufs.simulador_bolsa.wallet.PortfolioId;
import br.ufs.simulador_bolsa.wallet.PortfolioRepository;
import br.ufs.simulador_bolsa.wallet.WalletSnapshot;
import lombok.RequiredArgsConstructor;

/**
 * Serviço de liquidação bilateral atômica (ACID).
 * Efetiva a transação entre comprador e vendedor em uma única operação de banco de dados.
 * Gerencia a prevenção de deadlock (ordenação canônica por ID), cálculo ponderado do preço médio
 * e devolução imediata da sobra da reserva de compra.
 */
@Service
@RequiredArgsConstructor
public class SettlementService implements SettlementPort {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final AssetRepository assetRepository;
    private final PortfolioRepository portfolioRepository;
    private final TradeRepository tradeRepository;
    private final DatabaseWalletService walletService;
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    private OrderPersistenceService orderPersistenceService;

    @Autowired(required = false)
    private AssetCatalog assetCatalog;

    @Autowired(required = false)
    private MarketEventPublisher eventPublisher;

    @Autowired(required = false)
    private MarketMetricsService metricsService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settle(Trade trade) {
        long startNanos = System.nanoTime();
        Long buyDbId = trade.buy().databaseId();
        Long sellDbId = trade.sell().databaseId();

        if (buyDbId == null && orderPersistenceService != null) {
            buyDbId = orderPersistenceService.persistOpenOrder(trade.buy());
        }
        if (sellDbId == null && orderPersistenceService != null) {
            sellDbId = orderPersistenceService.persistOpenOrder(trade.sell());
        }

        if (buyDbId != null && sellDbId != null) {
            TradeExecutionCommand cmd = new TradeExecutionCommand(
                    buyDbId,
                    sellDbId,
                    trade.buy().symbol(),
                    (int) trade.quantity(),
                    trade.price()
            );
            TradeEntity saved = settleTrade(cmd);
            double latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            if (metricsService != null) {
                metricsService.recordTradeExecuted(trade.quantity(), trade.price(), latencyMs);
            }

            long resolvedVolume = trade.quantity();
            if (tradeRepository != null) {
                Long persistedVolume = tradeRepository.sumVolumeByTicker(trade.buy().symbol());
                if (persistedVolume != null && persistedVolume > 0) {
                    resolvedVolume = persistedVolume;
                }
            }
            final long totalVolume = resolvedVolume;

            log.info("TRADE_EXECUTED tradeId={} buyOrderId={} sellOrderId={} symbol={} qty={} price={} volume={} latencyMs={}",
                    saved.getId(), trade.buy().orderId(), trade.sell().orderId(),
                    trade.buy().symbol(), trade.quantity(), trade.price(), totalVolume,
                    String.format(java.util.Locale.ROOT, "%.3f", latencyMs));

            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        atualizarCatalogoAposNegocio(trade, totalVolume);
                        broadcastTradeExecuted(saved, trade, totalVolume);
                    }
                });
            } else {
                atualizarCatalogoAposNegocio(trade, totalVolume);
                broadcastTradeExecuted(saved, trade, totalVolume);
            }
        } else {
            log.error("SETTLEMENT_REJECTED_MISSING_PERSISTED_ID buyOrderId={} buyDbId={} sellOrderId={} sellDbId={}",
                    trade.buy().orderId(), buyDbId, trade.sell().orderId(), sellDbId);
            throw new IllegalStateException("Falha na liquidação: ordem sem ID persistido no banco de dados");
        }
    }

    private void atualizarCatalogoAposNegocio(Trade trade, long totalVolume) {
        if (assetCatalog == null) return;
        if (tradeRepository != null) {
            assetCatalog.syncMarketData(trade.buy().symbol(), null, trade.price(), totalVolume);
        } else {
            assetCatalog.recordTrade(trade.buy().symbol(), trade.price(), trade.quantity());
        }
    }

    private void broadcastTradeExecuted(TradeEntity saved, Trade trade, long totalVolume) {
        if (eventPublisher == null) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tradeId", String.valueOf(saved.getId()));
            payload.put("symbol", trade.buy().symbol());
            payload.put("quantity", trade.quantity());
            payload.put("price", trade.price());
            payload.put("volume", totalVolume);
            payload.put("buyerUsername", trade.buy().userId());
            payload.put("sellerUsername", trade.sell().userId());
            payload.put("executedAt", Instant.now().toString());
            eventPublisher.publish("TRADE_EXECUTED", payload);
        } catch (Exception e) {
            log.warn("TRADE_BROADCAST_FAILED symbol={}", trade.buy().symbol(), e);
        }
    }

    /**
     * Executa a liquidação dos dois lados em uma única transação atômica.
     */
    @Transactional(rollbackFor = Exception.class)
    public TradeEntity settleTrade(TradeExecutionCommand command) {
        log.info("SETTLEMENT_STARTED buyOrderId={} sellOrderId={} symbol={} qty={} price={}",
                command.buyOrderId(), command.sellOrderId(), command.symbol(), command.quantity(), command.executedPrice());

        // 1. Carrega as ordens
        OrderEntity buyOrder = orderRepository.findById(command.buyOrderId())
                .orElseThrow(() -> new IllegalArgumentException("Ordem de compra não encontrada: " + command.buyOrderId()));
        OrderEntity sellOrder = orderRepository.findById(command.sellOrderId())
                .orElseThrow(() -> new IllegalArgumentException("Ordem de venda não encontrada: " + command.sellOrderId()));

        User buyer = buyOrder.getUsuario();
        User seller = sellOrder.getUsuario();

        if (buyer.getId().equals(seller.getId())) {
            throw new IllegalStateException("Autonegociação rejeitada: o mesmo usuário não pode comprar e vender para si mesmo");
        }

        // 2. Prevenção de Deadlock: ordenação canônica por ID de usuário ao consultar/atualizar
        User firstLockUser;
        User secondLockUser;
        if (buyer.getId() < seller.getId()) {
            firstLockUser = userRepository.findById(buyer.getId()).orElseThrow();
            secondLockUser = userRepository.findById(seller.getId()).orElseThrow();
        } else {
            firstLockUser = userRepository.findById(seller.getId()).orElseThrow();
            secondLockUser = userRepository.findById(buyer.getId()).orElseThrow();
        }

        User lockedBuyer = buyer.getId().equals(firstLockUser.getId()) ? firstLockUser : secondLockUser;
        User lockedSeller = seller.getId().equals(firstLockUser.getId()) ? firstLockUser : secondLockUser;

        // 3. Carrega o ativo
        AssetEntity asset = assetRepository.findByTicker(command.symbol().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Ativo não encontrado: " + command.symbol()));

        int qty = command.quantity();
        BigDecimal execPrice = command.executedPrice();
        BigDecimal totalFinancial = execPrice.multiply(BigDecimal.valueOf(qty));

        // 4. Devolução da sobra de reserva do comprador e liberação de reservas
        // O comprador reservou com base no precoAlvo da ordem
        BigDecimal targetPrice = buyOrder.getPrecoAlvo();
        BigDecimal reservedPerShare = targetPrice != null ? targetPrice : execPrice;
        BigDecimal totalReservedForExecution = reservedPerShare.multiply(BigDecimal.valueOf(qty));

        // Se o preço executado foi menor que o preço alvo da ordem, a diferença (sobra) volta pro disponível
        BigDecimal refundRemainder = totalReservedForExecution.subtract(totalFinancial);
        if (refundRemainder.signum() > 0) {
            log.info("RESERVATION_SURPLUS_REFUNDED buyerId={} refundAmount={}", lockedBuyer.getId(), refundRemainder);
        }

        // Débito financeiro real do comprador e crédito no vendedor
        lockedBuyer.setSaldo(lockedBuyer.getSaldo().subtract(totalFinancial));
        lockedSeller.setSaldo(lockedSeller.getSaldo().add(totalFinancial));
        userRepository.save(lockedBuyer);
        userRepository.save(lockedSeller);

        // 5. Transferência de custódia e cálculo do Preço Médio Ponderado
        // Vendedor: debita da carteira
        PortfolioEntity sellerPortfolio = portfolioRepository.findByUsuarioIdAndTicker(lockedSeller.getId(), asset.getTicker())
                .orElseThrow(() -> new IllegalStateException("Carteira do vendedor não encontrada para o ativo: " + asset.getTicker()));

        int sellerNewQty = sellerPortfolio.getQuantidade() - qty;
        if (sellerNewQty < 0) {
            throw new IllegalStateException("Quantidade insuficiente na carteira do vendedor");
        }
        sellerPortfolio.setQuantidade(sellerNewQty);
        if (sellerNewQty == 0) {
            sellerPortfolio.setPrecoMedio(BigDecimal.ZERO);
        }
        portfolioRepository.save(sellerPortfolio);

        // Comprador: credita ações e calcula novo Preço Médio
        PortfolioEntity buyerPortfolio = portfolioRepository.findByUsuarioIdAndTicker(lockedBuyer.getId(), asset.getTicker())
                .orElseGet(() -> {
                    PortfolioEntity p = new PortfolioEntity();
                    p.setId(new PortfolioId(lockedBuyer.getId(), asset.getId()));
                    p.setUsuario(lockedBuyer);
                    p.setAtivo(asset);
                    p.setQuantidade(0);
                    p.setPrecoMedio(BigDecimal.ZERO);
                    return p;
                });

        int buyerOldQty = buyerPortfolio.getQuantidade() != null ? buyerPortfolio.getQuantidade() : 0;
        BigDecimal buyerOldAvgPrice = buyerPortfolio.getPrecoMedio() != null ? buyerPortfolio.getPrecoMedio() : BigDecimal.ZERO;

        int buyerNewQty = buyerOldQty + qty;
        BigDecimal buyerOldTotalValue = buyerOldAvgPrice.multiply(BigDecimal.valueOf(buyerOldQty));
        BigDecimal buyerNewTotalValue = buyerOldTotalValue.add(totalFinancial);
        BigDecimal newAvgPrice = buyerNewTotalValue.divide(BigDecimal.valueOf(buyerNewQty), 2, RoundingMode.HALF_EVEN);

        buyerPortfolio.setQuantidade(buyerNewQty);
        buyerPortfolio.setPrecoMedio(newAvgPrice);
        portfolioRepository.save(buyerPortfolio);

        // 6. Atualização de status das ordens
        updateOrderStatus(buyOrder, qty);
        updateOrderStatus(sellOrder, qty);
        orderRepository.save(buyOrder);
        orderRepository.save(sellOrder);

        // 7. Atualiza preço de mercado do ativo
        asset.setPrecoAtual(execPrice);
        assetRepository.save(asset);

        // 8. Persiste no histórico de negociações
        TradeEntity trade = TradeEntity.builder()
                .ordemCompra(buyOrder)
                .ordemVenda(sellOrder)
                .ativo(asset)
                .quantidadeExecutada(qty)
                .precoExecutado(execPrice)
                .build();
        TradeEntity savedTrade = tradeRepository.save(trade);

        log.info("TRADE_SETTLED tradeId={} symbol={} qty={} price={} buyer={} seller={}",
                savedTrade.getId(), asset.getTicker(), qty, execPrice, lockedBuyer.getUsername(), lockedSeller.getUsername());
        log.info("PORTFOLIO_UPDATED tradeId={} buyer={} buyerNewBalance={} buyerNewShares={} buyerAvgPrice={} seller={} sellerNewBalance={} sellerNewShares={}",
                savedTrade.getId(), lockedBuyer.getUsername(), lockedBuyer.getSaldo(), buyerPortfolio.getQuantidade(), buyerPortfolio.getPrecoMedio(),
                lockedSeller.getUsername(), lockedSeller.getSaldo(), sellerPortfolio.getQuantidade());

        // 9. Liberação de reservas em memória e notificações (apenas após o commit bem-sucedido)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    walletService.releaseFunds(String.valueOf(lockedBuyer.getId()), totalReservedForExecution);
                    walletService.releaseShares(String.valueOf(lockedSeller.getId()), asset.getTicker(), qty);
                    notifyWalletUpdate(lockedBuyer.getUsername(), lockedBuyer.getId());
                    notifyWalletUpdate(lockedSeller.getUsername(), lockedSeller.getId());
                    log.info("CLIENTS_NOTIFIED tradeId={} buyer={} seller={}", savedTrade.getId(), lockedBuyer.getUsername(), lockedSeller.getUsername());
                }
            });
        } else {
            walletService.releaseFunds(String.valueOf(lockedBuyer.getId()), totalReservedForExecution);
            walletService.releaseShares(String.valueOf(lockedSeller.getId()), asset.getTicker(), qty);
            notifyWalletUpdate(lockedBuyer.getUsername(), lockedBuyer.getId());
            notifyWalletUpdate(lockedSeller.getUsername(), lockedSeller.getId());
            log.info("CLIENTS_NOTIFIED tradeId={} buyer={} seller={}", savedTrade.getId(), lockedBuyer.getUsername(), lockedSeller.getUsername());
        }

        return savedTrade;
    }

    private void updateOrderStatus(OrderEntity order, int executedQty) {
        int remaining = order.getQuantidade() - executedQty;
        if (remaining <= 0) {
            order.setStatus("EXECUTADA");
        } else {
            order.setQuantidade(remaining);
        }
    }

    private void notifyWalletUpdate(String username, Long userId) {
        try {
            WalletSnapshot snapshot = walletService.getSnapshot(String.valueOf(userId));
            MarketEvent<WalletSnapshot> event = new MarketEvent<>(
                    "WALLET_UPDATED",
                    UUID.randomUUID().toString(),
                    UUID.randomUUID(),
                    Instant.now(),
                    snapshot
            );
            messagingTemplate.convertAndSendToUser(username, "/queue/replies", event);
        } catch (Exception e) {
            log.error("FAILED_TO_SEND_WALLET_UPDATE user={}", username, e);
        }
    }
}
