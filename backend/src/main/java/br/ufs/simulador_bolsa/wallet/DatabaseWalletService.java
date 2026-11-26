package br.ufs.simulador_bolsa.wallet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Implementação da carteira conectada ao PostgreSQL.
 * O saldo persistido e as custódias reais vêm do banco de dados.
 * As reservas ativas (fundos bloqueados por ordens de compra em aberto e ações bloqueadas por ordens de venda)
 * são coordenadas de forma concorrente e thread-safe.
 */
@Service
@Primary
@RequiredArgsConstructor
public class DatabaseWalletService implements WalletPort {

    private static final Logger log = LoggerFactory.getLogger(DatabaseWalletService.class);

    private final UserRepository userRepository;
    private final PortfolioRepository portfolioRepository;
    private final AssetRepository assetRepository;

    // Rastreia reservas ativas em memória com thread-safety
    // userId -> valor monetário reservado
    private final Map<String, BigDecimal> reservedBalances = new ConcurrentHashMap<>();
    // userId|symbol -> quantidade de ações reservadas
    private final Map<String, Long> reservedShares = new ConcurrentHashMap<>();

    private Long parseUserId(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private User resolveUser(String userId) {
        Long id = parseUserId(userId);
        if (id != null) {
            Optional<User> byId = userRepository.findById(id);
            if (byId.isPresent()) return byId.get();
        }
        return userRepository.findByUsername(userId).orElse(null);
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

    public BigDecimal getReservedBalance(String userId) {
        if (userId == null) return BigDecimal.ZERO;
        BigDecimal direct = reservedBalances.get(userId);
        if (direct != null) {
            return direct;
        }
        User user = resolveUser(userId);
        if (user != null) {
            BigDecimal byName = reservedBalances.get(user.getUsername());
            if (byName != null) return byName;
            if (user.getId() != null) {
                BigDecimal byId = reservedBalances.get(String.valueOf(user.getId()));
                if (byId != null) return byId;
            }
        }
        return BigDecimal.ZERO;
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

    public long getReservedShares(String userId, String symbol) {
        if (userId == null || symbol == null) return 0;
        String sym = symbol.toUpperCase();
        Long direct = reservedShares.get(userId + "|" + sym);
        if (direct != null) {
            return direct;
        }
        User user = resolveUser(userId);
        if (user != null) {
            Long byName = reservedShares.get(user.getUsername() + "|" + sym);
            if (byName != null) return byName;
            if (user.getId() != null) {
                Long byId = reservedShares.get(user.getId() + "|" + sym);
                if (byId != null) return byId;
            }
        }
        return 0L;
    }

    @Override
    public synchronized boolean reserveFunds(String userId, BigDecimal amount) {
        requirePositive(amount);
        BigDecimal available = getAvailableBalance(userId);
        if (available.compareTo(amount) >= 0) {
            String targetKey = findExistingBalanceKey(userId);
            reservedBalances.merge(targetKey, amount, BigDecimal::add);
            log.info("FUNDS_RESERVED userId={} amount={} newReserved={}", userId, amount, reservedBalances.get(targetKey));
            return true;
        }
        log.warn("FUNDS_RESERVE_FAILED userId={} requested={} available={}", userId, amount, available);
        return false;
    }

    @Override
    public synchronized boolean reserveShares(String userId, String symbol, long quantity) {
        requirePositive(quantity);
        String sym = symbol.toUpperCase();
        long available = getAvailableShares(userId, sym);
        if (available >= quantity) {
            String key = findExistingSharesKey(userId, sym);
            reservedShares.merge(key, quantity, Long::sum);
            log.info("SHARES_RESERVED userId={} symbol={} quantity={} newReserved={}", userId, sym, quantity, reservedShares.get(key));
            return true;
        }
        log.warn("SHARES_RESERVE_FAILED userId={} symbol={} requested={} available={}", userId, sym, quantity, available);
        return false;
    }

    @Override
    public synchronized void releaseFunds(String userId, BigDecimal amount) {
        requirePositive(amount);
        String targetKey = findExistingBalanceKey(userId);
        reservedBalances.computeIfPresent(targetKey, (k, current) -> {
            BigDecimal remaining = current.subtract(amount);
            return remaining.signum() > 0 ? remaining : null;
        });
        log.info("FUNDS_RELEASED userId={} amount={} currentReserved={}", userId, amount, getReservedBalance(userId));
    }

    @Override
    public synchronized void releaseShares(String userId, String symbol, long quantity) {
        requirePositive(quantity);
        String sym = symbol.toUpperCase();
        String key = findExistingSharesKey(userId, sym);
        reservedShares.computeIfPresent(key, (k, current) -> {
            long remaining = current - quantity;
            return remaining > 0 ? remaining : null;
        });
        log.info("SHARES_RELEASED userId={} symbol={} quantity={} currentReserved={}", userId, sym, quantity, getReservedShares(userId, sym));
    }

    public synchronized void clearInMemoryReservations() {
        reservedBalances.clear();
        reservedShares.clear();
    }

    private String findExistingBalanceKey(String userId) {
        if (reservedBalances.containsKey(userId)) {
            return userId;
        }
        User user = resolveUser(userId);
        if (user != null) {
            if (reservedBalances.containsKey(user.getUsername())) {
                return user.getUsername();
            }
            if (user.getId() != null && reservedBalances.containsKey(String.valueOf(user.getId()))) {
                return String.valueOf(user.getId());
            }
        }
        return userId;
    }

    private String findExistingSharesKey(String userId, String sym) {
        String directKey = userId + "|" + sym;
        if (reservedShares.containsKey(directKey)) {
            return directKey;
        }
        User user = resolveUser(userId);
        if (user != null) {
            String byName = user.getUsername() + "|" + sym;
            if (reservedShares.containsKey(byName)) {
                return byName;
            }
            if (user.getId() != null) {
                String byId = user.getId() + "|" + sym;
                if (reservedShares.containsKey(byId)) {
                    return byId;
                }
            }
        }
        return directKey;
    }

    /**
     * Retorna o snapshot completo da carteira do usuário (saldo disponível, reservado e posições com preço médio)
     * exatamente como contratado pelo Home Broker (/app/wallet/me).
     */
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
