package br.ufs.simulador_bolsa.orders;

import java.time.Instant;
import java.util.List;
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
import br.ufs.simulador_bolsa.market.Asset;
import br.ufs.simulador_bolsa.market.AssetCatalog;

//é o controller das ordens
//o @Controller é o aviso ao spring que vai ser roteamento de msg STOMP
@RequiredArgsConstructor
@Controller
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final AssetCatalog catalog;
    private final CurrentUserResolver users;

    //é por onde a ordem chega
    @MessageMapping("/orders/create")
    //envia a resposta somente para o user que enviou a ordem
    @SendToUser(destinations = "/queue/orders", broadcast = false)
    public MarketEvent<OrderResult> submit(SubmitOrderMessage message, SimpMessageHeaderAccessor headers) {
        String userId = users.resolve(headers);//pega o user
        SubmitOrderMessage body = message != null ? message //validação caso a ordem seja toda vazia
                : new SubmitOrderMessage(null, null, null, null, null);

        //processa a ordem lá no orderSertvice
        OrderResult result = orderService.submit(body.toRequest(userId));
        //e retorna a confirmação
        return event("ORDER_ACK", result.clientOrderId(), result);
    }

    //por aqui é o pedido de listagem de ações
    @MessageMapping("/assets/list")
    @SendToUser(destinations = "/queue/replies", broadcast = false)
    public MarketEvent<List<Asset>> listAssets() {
        //retorna as listas do catalogo, só
        return event("ASSET_LIST", null, catalog.listAll());
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
