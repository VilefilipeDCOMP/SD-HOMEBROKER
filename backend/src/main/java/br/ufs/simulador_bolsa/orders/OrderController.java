package br.ufs.simulador_bolsa.orders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import br.ufs.simulador_bolsa.communication.MarketEvent;

//é o controller das ordens
//o @Controller é o aviso ao spring que vai ser roteamento de msg STOMP
@RequiredArgsConstructor
@Controller
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final CurrentUserResolver users;
    private final MatchingEngine matchingEngine;

    //é por onde a ordem chega
    @MessageMapping("/orders/create")
    //envia a resposta somente para o user que enviou a ordem
    @SendToUser(destinations = "/queue/orders", broadcast = false)
    public MarketEvent<OrderResult> submit(SubmitOrderMessage message, SimpMessageHeaderAccessor headers) {
        String userId = users.resolve(headers);//pega o user
        SubmitOrderMessage body = message != null ? message //validação caso a ordem seja toda vazia
                : new SubmitOrderMessage(null, null, null, null, null);

        //processa a ordem lá no orderService
        OrderResult result = orderService.submit(body.toRequest(userId));
        //e retorna a confirmação
        return event("ORDER_ACK", result.clientOrderId(), result);
    }

    //consulta o status atual de uma ordem
    @MessageMapping("/orders/status")
    @SendToUser(destinations = "/queue/replies", broadcast = false)
    public MarketEvent<OrderResult> status(OrderStatusMessage message, SimpMessageHeaderAccessor headers) {
        String userId = users.resolve(headers);
        String orderId = message != null ? message.orderId() : null;

        return orderService.findByIdForUser(orderId, userId)
                .map(order -> event("ORDER_STATUS", order.clientOrderId(), OrderResult.of(order)))
                .orElseGet(() -> event("ORDER_NOT_FOUND", orderId, null));
    }

    //cancela uma ordem aberta do próprio usuário
    @MessageMapping("/orders/cancel")
    @SendToUser(destinations = "/queue/orders", broadcast = false)
    public MarketEvent<OrderResult> cancel(OrderStatusMessage message, SimpMessageHeaderAccessor headers) {
        String userId = users.resolve(headers);
        String orderId = message != null ? message.orderId() : null;

        return orderService.cancelOrder(orderId, userId)
                .map(order -> {
                    if (order.status() == OrderStatus.CANCELLED) {
                        return event("ORDER_CANCELLED", order.clientOrderId(), OrderResult.of(order));
                    }
                    return event("ORDER_CANCEL_REJECTED", order.clientOrderId(), OrderResult.of(order));
                })
                .orElseGet(() -> event("ORDER_CANCEL_REJECTED", orderId,
                        new OrderResult(orderId, null, null, null, null, null, OrderStatus.REJECTED, RejectReason.INVALID_FIELDS)));
    }

    //snapshot do livro de ofertas de um ativo
    @MessageMapping("/orderbook/snapshot")
    @SendToUser(destinations = "/queue/replies", broadcast = false)
    public MarketEvent<Map<String, Object>> orderBookSnapshot(Map<String, String> payload) {
        String symbol = payload != null && payload.get("symbol") != null ? payload.get("symbol") : "PETR4";
        return event("ORDER_BOOK_SNAPSHOT", symbol, matchingEngine.getBookSnapshot(symbol));
    }

    //historico de ordens do user, ordenada das mais antigas para as mais recentes
    @MessageMapping("/orders/mine")
    @SendToUser(destinations = "/queue/replies", broadcast = false)
    public MarketEvent<List<OrderResult>> myOrders(SimpMessageHeaderAccessor headers) {
        String userId = users.resolve(headers);
        List<OrderResult> orders = orderService.findByUser(userId).stream()
                .map(OrderResult::of)
                .toList();
        return event("ORDER_HISTORY", null, orders);
    }

    //exception para caso o usuario tenha enviado json quebrado ou algo assim
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/orders", broadcast = false)
    public MarketEvent<OrderResult> handleMalformedMessage(Exception exception) {
        log.warn("ORDER_MALFORMED_MESSAGE {}", exception.toString());
        //retorna a msg de dados inválidos
        return event("ORDER_ACK", null, OrderResult.rejected(null, RejectReason.INVALID_FIELDS));
    }

    //marca o evento com o data hora e id do evento
    private static <T> MarketEvent<T> event(String type, String requestId, T data) {
        return new MarketEvent<>(type, requestId, UUID.randomUUID(), Instant.now(), data);
    }
}
