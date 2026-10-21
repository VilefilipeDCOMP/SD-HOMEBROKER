package br.ufs.simulador_bolsa.wallet;

import java.math.BigDecimal;

//criei essa interface para desacoplar só, para quando mudarmos para um banco, não precisar nada
//acho que é auto explicativo o restante do código
public interface WalletPort {

    BigDecimal getAvailableBalance(String userId);

    long getAvailableShares(String userId, String symbol);

    boolean reserveFunds(String userId, BigDecimal amount);

    boolean reserveShares(String userId, String symbol, long quantity);

    void releaseFunds(String userId, BigDecimal amount);

    void releaseShares(String userId, String symbol, long quantity);
}
