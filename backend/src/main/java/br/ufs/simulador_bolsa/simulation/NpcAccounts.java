package br.ufs.simulador_bolsa.simulation;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;

@Service
@Profile("simulation")
class NpcAccounts {

    private static final String NPC_NAME = "Investidor virtual do simulador";

    private final UserRepository users;
    private final BigDecimal initialBalance;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    NpcAccounts(UserRepository users, @Value("${market.initial-balance:10000.00}") BigDecimal initialBalance) {
        this.users = users;
        this.initialBalance = initialBalance;
    }

    String ensureAccount(String username) {
        User user = users.findByUsername(username).orElseGet(() -> users.save(User.builder()
                .username(username)
                .nome(NPC_NAME)
                .senhaHash(encoder.encode(UUID.randomUUID().toString()))
                .saldo(initialBalance)
                .build()));

        // O cadastro público pode ter ocupado o nome antes de o perfil ser ativado.
        if (!NPC_NAME.equals(user.getNome())) {
            throw new IllegalStateException("Nome do NPC já pertence a outro investidor: " + username);
        }
        return user.getUsername();
    }
}
