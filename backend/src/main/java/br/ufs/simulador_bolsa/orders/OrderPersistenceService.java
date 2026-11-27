package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.market.Asset;
import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.wallet.PortfolioEntity;
import br.ufs.simulador_bolsa.wallet.PortfolioId;
import br.ufs.simulador_bolsa.wallet.PortfolioRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistenceService.class);

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final AssetCatalog assetCatalog;
    private final PortfolioRepository portfolioRepository;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwordEncoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    @Transactional
    public void seedDefaultAssetsIfNeeded() {
        for (Asset asset : assetCatalog.listAll()) {
            assetRepository.findByTicker(asset.symbol()).orElseGet(() -> {
                AssetEntity entity = AssetEntity.builder()
                        .ticker(asset.symbol())
                        .nomeEmpresa(asset.name())
                        .precoAtual(asset.currentPrice())
                        .build();
                return assetRepository.save(entity);
            });
        }
        seedDemoSellerIfNeeded();
    }

    private void seedDemoSellerIfNeeded() {
        String sellerUsername = "vendedor_demo";
        User seller = userRepository.findByUsername(sellerUsername).orElseGet(() -> {
            User u = User.builder()
                    .username(sellerUsername)
                    .nome("Vendedor Demo")
                    .email("vendedor_demo@simulador.local")
                    .senhaHash(passwordEncoder.encode("senha123"))
                    .saldo(new BigDecimal("10000.00"))
                    .build();
            return userRepository.save(u);
        });

        // Concede 100 ações de cada ativo para o vendedor_demo
        for (Asset asset : assetCatalog.listAll()) {
            AssetEntity assetEntity = ensureAsset(asset.symbol());
            PortfolioId pid = new PortfolioId(seller.getId(), assetEntity.getId());
            if (!portfolioRepository.existsById(pid)) {
                PortfolioEntity p = PortfolioEntity.builder()
                        .id(pid)
                        .usuario(seller)
                        .ativo(assetEntity)
                        .quantidade(100)
                        .precoMedio(asset.currentPrice())
                        .build();
                portfolioRepository.save(p);
                log.info("SEEDED_DEMO_SELLER_SHARES user={} asset={} qty=100", sellerUsername, asset.symbol());
            }
        }
    }

    @Transactional
    public AssetEntity ensureAsset(String symbol) {
        String sym = symbol.trim().toUpperCase();
        return assetRepository.findByTicker(sym).orElseGet(() -> {
            Asset catalogAsset = assetCatalog.findBySymbol(sym)
                    .orElse(new Asset(sym, sym, new BigDecimal("10.00")));
            AssetEntity entity = AssetEntity.builder()
                    .ticker(catalogAsset.symbol())
                    .nomeEmpresa(catalogAsset.name())
                    .precoAtual(catalogAsset.currentPrice())
                    .build();
            return assetRepository.save(entity);
        });
    }

    @Transactional(readOnly = true)
    public Optional<User> resolveUser(String userIdOrUsername) {
        if (userIdOrUsername == null || userIdOrUsername.isBlank()) {
            return Optional.empty();
        }
        try {
            long id = Long.parseLong(userIdOrUsername.trim());
            Optional<User> byId = userRepository.findById(id);
            if (byId.isPresent()) {
                return byId;
            }
        } catch (NumberFormatException ignored) {
        }
        return userRepository.findByUsername(userIdOrUsername.trim());
    }

    @Transactional
    public Long persistOpenOrder(Order order) {
        if (order == null) return null;
        if (order.databaseId() != null) {
            return order.databaseId();
        }

        Optional<User> userOpt = resolveUser(order.userId());
        if (userOpt.isEmpty()) {
            return null;
        }

        AssetEntity asset = ensureAsset(order.symbol());
        String tipo = order.side() == OrderSide.BUY ? "COMPRA" : "VENDA";

        OrderEntity entity = OrderEntity.builder()
                .usuario(userOpt.get())
                .ativo(asset)
                .tipo(tipo)
                .quantidade((int) order.remainingQuantity())
                .precoAlvo(order.price())
                .status("ABERTA")
                .build();

        OrderEntity saved = orderRepository.save(entity);
        order.assignDatabaseId(saved.getId());
        log.info("ORDER_PERSISTED orderId={} dbId={} userId={} symbol={} side={} qty={} price={} status=ABERTA",
                order.orderId(), saved.getId(), order.userId(), order.symbol(), tipo, saved.getQuantidade(), saved.getPrecoAlvo());
        return saved.getId();
    }

    @Transactional
    public void markOrderCancelled(Order order) {
        if (order == null || order.databaseId() == null) {
            throw new IllegalStateException("Ordem sem registro no banco");
        }

        OrderEntity entity = orderRepository.findById(order.databaseId())
                .orElseThrow(() -> new IllegalStateException("Ordem não encontrada no banco"));

        if (!"ABERTA".equals(entity.getStatus())) {
            throw new IllegalStateException("Ordem não está aberta no banco");
        }

        entity.setStatus("CANCELADA");
        orderRepository.save(entity);

        log.info("ORDER_DB_CANCELLED orderId={} dbId={}", order.orderId(), entity.getId());
    }
}
