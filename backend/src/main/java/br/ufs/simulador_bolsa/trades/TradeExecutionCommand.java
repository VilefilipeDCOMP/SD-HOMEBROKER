package br.ufs.simulador_bolsa.trades;

import java.math.BigDecimal;

public record TradeExecutionCommand(
        Long buyOrderId,
        Long sellOrderId,
        String symbol,
        int quantity,
        BigDecimal executedPrice
) {
    public TradeExecutionCommand {
        if (buyOrderId == null || sellOrderId == null) {
            throw new IllegalArgumentException("IDs de ordem de compra e venda são obrigatórios");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Símbolo do ativo é obrigatório");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantidade executada deve ser positiva");
        }
        if (executedPrice == null || executedPrice.signum() <= 0) {
            throw new IllegalArgumentException("Preço executado deve ser positivo");
        }
    }
}
