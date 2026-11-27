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
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistenceService.class);

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final AssetCatalog assetCatalog;

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
        if (order == null || order.databaseId() == null) return;
        orderRepository.findById(order.databaseId()).ifPresent(entity -> {
            entity.setStatus("CANCELADA");
            orderRepository.save(entity);
            log.info("ORDER_DB_CANCELLED orderId={} dbId={}", order.orderId(), entity.getId());
        });
    }
}
