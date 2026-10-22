package br.ufs.simulador_bolsa.communication;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class MarketEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public <T> void publish(String type, T data) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("O tipo do evento é obrigatório");
        }

        if (data == null) {
            throw new IllegalArgumentException("Os dados do evento são obrigatórios");
        }

        var event = new MarketEvent<>(
                type,
                null,
                UUID.randomUUID(),
                Instant.now(),
                data
        );

        messagingTemplate.convertAndSend("/topic/market", event);
    }

}
