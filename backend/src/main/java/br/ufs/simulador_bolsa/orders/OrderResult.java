package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;

//se o OrderRequest é como chega do cliente
//o Result(ou response) é como sai para o cliente
public record OrderResult(
        String orderId,
        String clientOrderId,
        String symbol,
        OrderSide side,
        Long quantity,
        BigDecimal price,
        OrderStatus status,
        RejectReason reason
) {
    public static OrderResult of(Order order) {
        return new OrderResult(
                order.orderId(),
                order.clientOrderId(),
                order.symbol(),
                order.side(),
                order.quantity(),
                order.price(),
                order.status(),
                order.rejectReason());
    }

    public static OrderResult rejected(OrderRequest request, RejectReason reason) {
        return new OrderResult(
                null,
                request == null ? null : request.clientOrderId(),
                request == null ? null : request.symbol(),
                request == null ? null : request.side(),
                request == null ? null : request.quantity(),
                request == null ? null : request.price(),
                OrderStatus.REJECTED,
                reason);
    }
}
