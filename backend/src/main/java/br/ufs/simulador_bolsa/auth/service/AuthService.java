package br.ufs.simulador_bolsa.auth.service;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import br.ufs.simulador_bolsa.auth.dto.AuthResponse;
import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthService {

    private static final Map<String, Integer> INITIAL_PORTFOLIO = Map.of(
        "PETR4", 0,
        "VALE3", 0,
        "ITUB4", 0,
        "MGLU3", 0
    );

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${market.initial-balance:10000.00}")
    private BigDecimal initialBalance;

    public AuthService(UserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    public AuthResponse register(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username não pode ser vazio");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Senha não pode ser vazia");
        }

        String cleanUsername = username.trim();
        if (userRepository.existsByUsername(cleanUsername)) {
            throw new IllegalArgumentException("Username já está em uso");
        }

        User user = User.builder()
                .username(cleanUsername)
                .senhaHash(encoder.encode(password))
                .saldo(initialBalance)
                .build();

        user = userRepository.save(user);
        log.info("Novo investidor cadastrado: {} (ID: {})", user.getUsername(), user.getId());

        String token = jwtService.generateToken(user.getUsername());

        return new AuthResponse(
            "AUTH_SUCCESS",
            token,
            user.getId(),
            user.getUsername(),
            user.getSaldo(),
            INITIAL_PORTFOLIO
        );
    }

    public AuthResponse login(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username não pode ser vazio");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Senha não pode ser vazia");
        }

        String cleanUsername = username.trim();
        User user = userRepository.findByUsername(cleanUsername)
                .orElseThrow(() -> new IllegalArgumentException("Usuário ou senha inválidos"));

        if (!encoder.matches(password, user.getSenhaHash())) {
            throw new IllegalArgumentException("Usuário ou senha inválidos");
        }

        log.info("Investidor autenticado com sucesso: {}", user.getUsername());
        String token = jwtService.generateToken(user.getUsername());

        return new AuthResponse(
            "AUTH_SUCCESS",
            token,
            user.getId(),
            user.getUsername(),
            user.getSaldo(),
            INITIAL_PORTFOLIO
        );
    }
}
