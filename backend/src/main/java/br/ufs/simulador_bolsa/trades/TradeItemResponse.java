package br.ufs.simulador_bolsa.trades;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TradeItemResponse(
        String tradeId,
        String orderId,
        String symbol,
        String side,
        long quantity,
        BigDecimal price,
        LocalDateTime executedAt
) {}
