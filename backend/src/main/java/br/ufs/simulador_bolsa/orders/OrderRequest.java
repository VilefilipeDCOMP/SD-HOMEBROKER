package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;
import java.util.Locale;

//é o pedido do cliente, ele quem vai entregar os dados para o OrderValidator,
//entao os dados ainda não estao validados, por isso podem ser nulos
//ele vai também concatenar o UserId da sessao atual
//é um record temporario, se for aceito pelo OrderValidator, vira Order, se não, vira pó(tadinho
public record OrderRequest(
        String userId,
        String clientOrderId,
        String symbol,
        OrderSide side,
        Long quantity,
        BigDecimal price
) {
    public OrderRequest {
        clientOrderId = clientOrderId == null ? null : clientOrderId.trim();
        symbol = symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
