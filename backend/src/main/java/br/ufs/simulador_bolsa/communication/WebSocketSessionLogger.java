package br.ufs.simulador_bolsa.communication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

@Component 
public class WebSocketSessionLogger {
    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionLogger.class);

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        var headers = StompHeaderAccessor.wrap(event.getMessage());

        log.info("Conexão STOMP estabelecida: sessão={}",headers.getSessionId());
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        log.info("Conexão STOMP encerrada: sessão={}, status={}",
                event.getSessionId(),
                event.getCloseStatus().getCode());
    }
}
