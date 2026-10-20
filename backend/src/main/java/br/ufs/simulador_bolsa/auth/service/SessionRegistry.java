package br.ufs.simulador_bolsa.auth.service;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import br.ufs.simulador_bolsa.auth.entity.User;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SessionRegistry {

    private final ConcurrentHashMap<String, User> activeSessions = new ConcurrentHashMap<>();

    public void register(String sessionId, User user) {
        activeSessions.put(sessionId, user);
        log.info("Sessão STOMP registrada: {} para o usuário {} (ID: {})", sessionId, user.getUsername(), user.getId());
    }

    public void unregister(String sessionId) {
        User removed = activeSessions.remove(sessionId);
        if (removed != null) {
            log.info("Sessão STOMP encerrada: {} do usuário {}", sessionId, removed.getUsername());
        }
    }

    public Optional<User> getUserBySession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    public boolean isConnected(Long userId) {
        return activeSessions.values().stream()
                .anyMatch(u -> u.getId().equals(userId));
    }

    public Collection<User> getConnectedUsers() {
        return Collections.unmodifiableCollection(activeSessions.values());
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }
}
