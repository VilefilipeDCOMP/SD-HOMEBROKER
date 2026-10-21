package br.ufs.simulador_bolsa.config;

import br.ufs.simulador_bolsa.auth.interceptor.AuthChannelInterceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final String[] allowedOrigins;
    private final AuthChannelInterceptor authChannelInterceptor;

    public WebSocketConfig(
            @Value("${app.websocket.allowed-origins}") String[] allowedOrigins,
            AuthChannelInterceptor authChannelInterceptor) {
        this.allowedOrigins = allowedOrigins;
        this.authChannelInterceptor = authChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                authChannelInterceptor,
                new ChannelInterceptor() {
                    @Override
                    public Message<?> preSend(Message<?> message, MessageChannel channel) {
                        var headers = StompHeaderAccessor.wrap(message);

                        if (headers.getCommand() == StompCommand.SEND) {
                            String destination = headers.getDestination();
                            if (destination == null || !destination.startsWith("/app/")) {
                                throw new MessageDeliveryException(
                                        message,
                                        "Envie comandos apenas para destinos /app/");
                            }
                        }

                        return message;
                    }
                });
    }
}
