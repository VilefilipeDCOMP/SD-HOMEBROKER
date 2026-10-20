package br.ufs.simulador_bolsa.orders;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;


//essa classe é o pedido depois que já chegou no sistema
//como os dados do pedido em si são imutaveis, só o status e o motivo de rejeição pode mudar
//essa classe prioriza por ordem de chegada usando o sequenceNumber e controla o status
@Getter
@Accessors(fluent = true)
public final class Order {

    private final String orderId;
    private final long sequenceNumber;
    private final String userId;
    private final String clientOrderId;
    private final String symbol;
    private final OrderSide side;
    private final long quantity;
    private final BigDecimal price;
    private final Instant createdAt;

    private volatile OrderStatus status = OrderStatus.RECEIVED;
    private volatile RejectReason rejectReason;

    //
    public Order(OrderRequest request, long sequenceNumber) {
        this.orderId = UUID.randomUUID().toString();
        this.sequenceNumber = sequenceNumber;
        this.userId = request.userId();
        this.clientOrderId = request.clientOrderId();
        this.symbol = request.symbol();
        this.side = request.side();
        this.quantity = request.quantity();
        this.price = request.price();
        this.createdAt = Instant.now();
    }

    public synchronized boolean accept() {
        if (status != OrderStatus.RECEIVED) {
            return false;
        }
        status = OrderStatus.ACCEPTED;
        return true;
    }

    public synchronized boolean reject(RejectReason reason) {
        if (status != OrderStatus.RECEIVED) {
            return false;
        }
        rejectReason = reason;
        status = OrderStatus.REJECTED;
        return true;
    }

    public BigDecimal totalValue() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
