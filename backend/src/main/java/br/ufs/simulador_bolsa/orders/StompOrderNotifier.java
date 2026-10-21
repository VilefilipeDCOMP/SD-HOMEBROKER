package br.ufs.simulador_bolsa.orders;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import br.ufs.simulador_bolsa.communication.MarketEvent;

/*
 * essa classe é a que realmente vai implementar a OrderNotifier, vai enviar o resultado pelo STOMP para o dono
 * pelo /user/queue/orders
 */
@RequiredArgsConstructor
@Component
public class StompOrderNotifier implements OrderNotifier {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void orderUpdated(Order order) {
        var event = new MarketEvent<>(
                "ORDER_UPDATED",
                order.clientOrderId(),
                UUID.randomUUID(),
                Instant.now(),
                OrderResult.of(order));

        messagingTemplate.convertAndSendToUser(order.userId(), "/queue/orders", event);
    }
}
