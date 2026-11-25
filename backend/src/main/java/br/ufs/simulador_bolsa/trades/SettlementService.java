package br.ufs.simulador_bolsa.trades;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.communication.MarketEvent;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.orders.OrderEntity;
import br.ufs.simulador_bolsa.orders.OrderRepository;
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
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final AssetRepository assetRepository;
    private final PortfolioRepository portfolioRepository;
    private final TradeRepository tradeRepository;
    private final DatabaseWalletService walletService;
    private final SimpMessagingTemplate messagingTemplate;

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

        // 4. Devolução da sobra de reserva do comprador
        // O comprador reservou com base no precoAlvo da ordem
        BigDecimal targetPrice = buyOrder.getPrecoAlvo();
        BigDecimal reservedPerShare = targetPrice != null ? targetPrice : execPrice;
        BigDecimal totalReservedForExecution = reservedPerShare.multiply(BigDecimal.valueOf(qty));

        // Libera a reserva total que havia sido bloqueada para essa parcela executada
        walletService.releaseFunds(String.valueOf(lockedBuyer.getId()), totalReservedForExecution);

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
        // Vendedor: libera ações reservadas e debita da carteira
        walletService.releaseShares(String.valueOf(lockedSeller.getId()), asset.getTicker(), qty);
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

        // 9. Notifica ambos os investidores via WebSocket com seus respectivos snapshots atualizados
        notifyWalletUpdate(lockedBuyer.getUsername(), lockedBuyer.getId());
        notifyWalletUpdate(lockedSeller.getUsername(), lockedSeller.getId());

        return savedTrade;
    }

    private void updateOrderStatus(OrderEntity order, int executedQty) {
        int remaining = order.getQuantidade() - executedQty;
        if (remaining <= 0) {
            order.setStatus("EXECUTADA");
            order.setQuantidade(0);
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
