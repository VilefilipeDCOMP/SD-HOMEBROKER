package br.ufs.simulador_bolsa.auth.interceptor;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.auth.service.JwtService;
import br.ufs.simulador_bolsa.auth.service.SessionRegistry;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class AuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SessionRegistry sessionRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {

        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            String token = extractToken(accessor);

            if (token == null || !jwtService.isValid(token)) {
                throw new IllegalStateException("Token JWT ausente ou inválido");
            }

            String username = jwtService.extractUsername(token);
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalStateException(
                            "Usuário não encontrado: " + username));

            StompPrincipal principal = new StompPrincipal(user);
            accessor.setUser(principal);

            String sessionId = accessor.getSessionId();
            if (sessionId != null) {
                sessionRegistry.register(sessionId, user);
            }
        }

        if (StompCommand.DISCONNECT.equals(command)) {
            String sessionId = accessor.getSessionId();
            if (sessionId != null) {
                sessionRegistry.unregister(sessionId);
            }
        }

        return message;
    }

    private String extractToken(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        String rawToken = accessor.getFirstNativeHeader("token");
        if (rawToken != null && !rawToken.isBlank()) {
            return rawToken;
        }

        return null;
    }
}
