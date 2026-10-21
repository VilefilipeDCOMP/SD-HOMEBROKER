package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.util.Locale;

//é o corpo JSON que envia
public record SubmitOrderMessage(
        String clientOrderId,
        String symbol,
        String side,
        Long quantity,
        String price
) {

    //deixa salvo no record temporario
    public OrderRequest toRequest(String userId) {
        return new OrderRequest(userId, clientOrderId, symbol, parseSide(side), quantity, parsePrice(price));
    }

    //o restante são as validações
    private static OrderSide parseSide(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OrderSide.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BigDecimal parsePrice(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
