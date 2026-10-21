package br.ufs.simulador_bolsa.wallet;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
 * Carteira em memória,por enquanto, até Bruno commitar a versão com o bd
 * Quando isso acontecer, retirar o @Componente pra nao dar erro
 */
@Component
public class InMemoryWallet implements WalletPort {

    //dinheiro inicial de toda conta
    private final BigDecimal initialBalance;

    //uma conta por user
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    //setando quanto vai ser o dinheiro inicial
    public InMemoryWallet(@Value("${app.wallet.initial-balance:10000.00}") BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    //usa o computeifAbsent para, se o user fizer alguma coisa e não tiver conta, ele vai criar a conta
    //se ele já estiver conta, ele só vai voltar o userId
    private Account account(String userId) {
        return accounts.computeIfAbsent(userId, id -> new Account(initialBalance));
    }

    @Override
    public BigDecimal getAvailableBalance(String userId) {
        return account(userId).availableBalance();
    }

    @Override
    public long getAvailableShares(String userId, String symbol) {
        return account(userId).availableShares(symbol);
    }


    //são as verificações do valor antes de chegar da conta
    @Override
    public boolean reserveFunds(String userId, BigDecimal amount) {
        requirePositive(amount);
        return account(userId).reserveFunds(amount);
    }

    @Override
    public boolean reserveShares(String userId, String symbol, long quantity) {
        requirePositive(quantity);
        return account(userId).reserveShares(symbol, quantity);
    }

    @Override
    public void releaseFunds(String userId, BigDecimal amount) {
        requirePositive(amount);
        account(userId).releaseFunds(amount);
    }

    @Override
    public void releaseShares(String userId, String symbol, long quantity) {
        requirePositive(quantity);
        account(userId).releaseShares(symbol, quantity);
    }

    public BigDecimal getReservedBalance(String userId) {
        return account(userId).reservedBalance();
    }

    public void depositShares(String userId, String symbol, long quantity) {
        requirePositive(quantity);
        account(userId).depositShares(symbol, quantity);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("O valor deve ser positivo");
        }
    }

    private static void requirePositive(long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("A quantidade deve ser positiva");
        }
    }

    //conta do user(investidor)
    private static final class Account {
        //dinheiro livre e dinheiro reservado
        private BigDecimal available;
        private BigDecimal reserved = BigDecimal.ZERO;
        private final Map<String, Long> sharesAvailable = new HashMap<>();
        private final Map<String, Long> sharesReserved = new HashMap<>();

        Account(BigDecimal initialBalance) {
            this.available = initialBalance;
        }

        synchronized BigDecimal availableBalance() {
            return available;
        }

        synchronized BigDecimal reservedBalance() {
            return reserved;
        }

        synchronized long availableShares(String symbol) {
            return sharesAvailable.getOrDefault(symbol, 0L);
        }

        //confere o saldo e logo dps reserva, tudo junto
        synchronized boolean reserveFunds(BigDecimal amount) {
            if (available.compareTo(amount) < 0) {
                return false;
            }
            available = available.subtract(amount);
            reserved = reserved.add(amount);
            return true;
        }

        //mesma coisa do de cima, só que com ações
        synchronized boolean reserveShares(String symbol, long quantity) {
            long current = sharesAvailable.getOrDefault(symbol, 0L);
            if (current < quantity) {
                return false;
            }
            sharesAvailable.put(symbol, current - quantity);
            sharesReserved.merge(symbol, quantity, Long::sum);
            return true;
        }

        //"libera" o saldo de reservado para disponivel
        //melhor colocar um exception pq liberar mais saldo do que realmente tem, é realmente um erro
        synchronized void releaseFunds(BigDecimal amount) {
            if (reserved.compareTo(amount) < 0) {
                throw new IllegalStateException("Valor reservado menor que o valor a liberar");
            }
            reserved = reserved.subtract(amount);
            available = available.add(amount);
        }

        synchronized void releaseShares(String symbol, long quantity) {
            long current = sharesReserved.getOrDefault(symbol, 0L);
            if (current < quantity) {
                throw new IllegalStateException("Quantidade reservada menor que a quantidade a liberar");
            }
            sharesReserved.put(symbol, current - quantity);
            sharesAvailable.merge(symbol, quantity, Long::sum);
        }

        //poe as ações ao disponivel
        synchronized void depositShares(String symbol, long quantity) {
            sharesAvailable.merge(symbol, quantity, Long::sum);
        }
    }
}
