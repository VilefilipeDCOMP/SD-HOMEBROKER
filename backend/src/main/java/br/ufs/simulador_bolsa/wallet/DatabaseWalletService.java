package br.ufs.simulador_bolsa.wallet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import lombok.RequiredArgsConstructor;

@Service
@Primary
@RequiredArgsConstructor
public class DatabaseWalletService implements WalletPort {

    private static final Logger log = LoggerFactory.getLogger(DatabaseWalletService.class);

    private final UserRepository userRepository;
    private final PortfolioRepository portfolioRepository;
    private final AssetRepository assetRepository;

    private final Map<String, BigDecimal> reservedBalances = new ConcurrentHashMap<>();
    private final Map<String, Long> reservedShares = new ConcurrentHashMap<>();

    /** A identidade canônica é o username; o ID numérico do banco nunca é usado como chave. */
    private User resolveUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userRepository.findByUsername(username.trim()).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getAvailableBalance(String userId) {
        User user = resolveUser(userId);
        if (user == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalBalance = user.getSaldo() != null ? user.getSaldo() : BigDecimal.ZERO;
        BigDecimal reserved = getReservedBalance(userId);
        BigDecimal available = totalBalance.subtract(reserved);
        return available.signum() >= 0 ? available : BigDecimal.ZERO;
    }

    public BigDecimal getReservedBalance(String username) {
        if (username == null) return BigDecimal.ZERO;
        BigDecimal reserved = reservedBalances.get(username);
        return reserved != null ? reserved : BigDecimal.ZERO;
    }

    @Override
    @Transactional(readOnly = true)
    public long getAvailableShares(String userId, String symbol) {
        if (symbol == null) return 0;
        User user = resolveUser(userId);
        if (user == null) {
            return 0;
        }
        Optional<PortfolioEntity> item = portfolioRepository.findByUsuarioIdAndTicker(user.getId(), symbol.toUpperCase());
        long totalShares = item.map(PortfolioEntity::getQuantidade).orElse(0);
        long reserved = getReservedShares(userId, symbol);
        long available = totalShares - reserved;
        return Math.max(available, 0);
    }

    public long getReservedShares(String username, String symbol) {
        if (username == null || symbol == null) return 0;
        Long reserved = reservedShares.get(username + "|" + symbol.toUpperCase());
        return reserved != null ? reserved : 0L;
    }

    @Override
    public synchronized boolean reserveFunds(String username, BigDecimal amount) {
        requirePositive(amount);
        BigDecimal available = getAvailableBalance(username);
        if (available.compareTo(amount) >= 0) {
            reservedBalances.merge(username, amount, BigDecimal::add);
            log.info("FUNDS_RESERVED username={} amount={} newReserved={}", username, amount, reservedBalances.get(username));
            return true;
        }
        log.warn("FUNDS_RESERVE_FAILED username={} requested={} available={}", username, amount, available);
        return false;
    }

    @Override
    public synchronized boolean reserveShares(String username, String symbol, long quantity) {
        requirePositive(quantity);
        String sym = symbol.toUpperCase();
        long available = getAvailableShares(username, sym);
        if (available >= quantity) {
            String key = username + "|" + sym;
            reservedShares.merge(key, quantity, Long::sum);
            log.info("SHARES_RESERVED username={} symbol={} quantity={} newReserved={}", username, sym, quantity, reservedShares.get(key));
            return true;
        }
        log.warn("SHARES_RESERVE_FAILED username={} symbol={} requested={} available={}", username, sym, quantity, available);
        return false;
    }

    @Override
    public synchronized void releaseFunds(String username, BigDecimal amount) {
        requirePositive(amount);
        reservedBalances.computeIfPresent(username, (k, current) -> {
            BigDecimal remaining = current.subtract(amount);
            return remaining.signum() > 0 ? remaining : null;
        });
        log.info("FUNDS_RELEASED username={} amount={} currentReserved={}", username, amount, getReservedBalance(username));
    }

    @Override
    public synchronized void releaseShares(String username, String symbol, long quantity) {
        requirePositive(quantity);
        String sym = symbol.toUpperCase();
        String key = username + "|" + sym;
        reservedShares.computeIfPresent(key, (k, current) -> {
            long remaining = current - quantity;
            return remaining > 0 ? remaining : null;
        });
        log.info("SHARES_RELEASED username={} symbol={} quantity={} currentReserved={}", username, sym, quantity, getReservedShares(username, sym));
    }

    public synchronized void clearInMemoryReservations() {
        reservedBalances.clear();
        reservedShares.clear();
    }

    /** Substitui atomicamente as reservas do usuário; usado pela reconciliação pós-commit. */
    public synchronized void replaceReservations(String username, BigDecimal funds, Map<String, Long> sharesBySymbol) {
        if (username == null || username.isBlank()) {
            return;
        }

        String prefix = username + "|";
        reservedShares.keySet().removeIf(key -> key.startsWith(prefix));

        if (funds == null || funds.signum() <= 0) {
            reservedBalances.remove(username);
        } else {
            reservedBalances.put(username, funds);
        }

        if (sharesBySymbol != null) {
            for (Map.Entry<String, Long> entry : sharesBySymbol.entrySet()) {
                String symbol = entry.getKey();
                Long quantity = entry.getValue();
                if (symbol != null && quantity != null && quantity > 0) {
                    reservedShares.put(username + "|" + symbol.trim().toUpperCase(Locale.ROOT), quantity);
                }
            }
        }

        log.info("RESERVATIONS_REPLACED user={} funds={} symbols={}", username, funds,
                sharesBySymbol == null ? List.of() : sharesBySymbol.keySet());
    }

    @Transactional(readOnly = true)
    public WalletSnapshot getSnapshot(String userId) {
        User user = resolveUser(userId);
        if (user == null) {
            return new WalletSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }

        BigDecimal total = user.getSaldo() != null ? user.getSaldo() : BigDecimal.ZERO;
        BigDecimal reserved = getReservedBalance(userId);
        BigDecimal available = total.subtract(reserved);
        if (available.signum() < 0) available = BigDecimal.ZERO;

        List<PortfolioEntity> items = portfolioRepository.findByUsuarioIdWithAsset(user.getId());
        List<WalletSnapshot.PositionItem> positions = new ArrayList<>();

        for (PortfolioEntity item : items) {
            String symbol = item.getAtivo().getTicker();
            long totalQty = item.getQuantidade() != null ? item.getQuantidade() : 0;
            long resQty = getReservedShares(userId, symbol);
            BigDecimal avgPrice = item.getPrecoMedio() != null ? item.getPrecoMedio() : BigDecimal.ZERO;

            positions.add(new WalletSnapshot.PositionItem(
                    symbol,
                    totalQty,
                    resQty,
                    avgPrice
            ));
        }

        return new WalletSnapshot(available, reserved, positions);
    }

    @Transactional
    public void grantShares(String username, String symbol, int quantity, BigDecimal avgPrice) {
        if (quantity <= 0) return;
        User user = resolveUser(username);
        if (user == null) {
            throw new IllegalArgumentException("Usuário não encontrado: " + username);
        }
        AssetEntity asset = assetRepository.findByTicker(symbol.toUpperCase()).orElseGet(() -> {
            AssetEntity created = AssetEntity.builder()
                    .ticker(symbol.toUpperCase())
                    .nomeEmpresa(symbol.toUpperCase())
                    .precoAtual(avgPrice != null ? avgPrice : new BigDecimal("10.00"))
                    .build();
            return assetRepository.save(created);
        });

        PortfolioId id = new PortfolioId(user.getId(), asset.getId());
        PortfolioEntity entity = portfolioRepository.findById(id).orElseGet(() -> {
            PortfolioEntity p = new PortfolioEntity();
            p.setId(id);
            p.setUsuario(user);
            p.setAtivo(asset);
            p.setQuantidade(0);
            p.setPrecoMedio(avgPrice != null ? avgPrice : BigDecimal.ZERO);
            return p;
        });

        entity.setQuantidade(entity.getQuantidade() + quantity);
        if (avgPrice != null) {
            entity.setPrecoMedio(avgPrice);
        }
        portfolioRepository.save(entity);
        log.info("SHARES_GRANTED user={} symbol={} qty={} avgPrice={}", username, symbol, quantity, avgPrice);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("O valor monetário deve ser positivo");
        }
    }

    private static void requirePositive(long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("A quantidade deve ser positiva");
        }
    }
}
