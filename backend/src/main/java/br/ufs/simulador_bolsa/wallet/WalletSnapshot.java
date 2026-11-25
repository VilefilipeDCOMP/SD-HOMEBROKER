package br.ufs.simulador_bolsa.wallet;

import java.math.BigDecimal;
import java.util.List;

public record WalletSnapshot(
        BigDecimal balance,
        BigDecimal reservedBalance,
        List<PositionItem> positions
) {
    public record PositionItem(
            String symbol,
            long quantity,
            long reservedQuantity,
            BigDecimal averagePrice
    ) {}
}
