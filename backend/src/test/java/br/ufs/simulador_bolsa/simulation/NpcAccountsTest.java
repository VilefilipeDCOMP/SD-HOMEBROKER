package br.ufs.simulador_bolsa.simulation;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcAccountsTest {

    private static final String USERNAME = "npc_conservador";
    private final UserRepository users = mock(UserRepository.class);
    private final NpcAccounts accounts = new NpcAccounts(users, new BigDecimal("10000.00"));

    @Test
    void provisionsOnceAndKeepsTheExistingBalance() {
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals(USERNAME, accounts.ensureAccount(USERNAME));
        ArgumentCaptor<User> account = ArgumentCaptor.forClass(User.class);
        verify(users).save(account.capture());
        User npc = account.getValue();
        assertEquals(new BigDecimal("10000.00"), npc.getSaldo());
        assertTrue(npc.getSenhaHash().startsWith("$2a$"));

        npc.setSaldo(new BigDecimal("37.80"));
        when(users.findByUsername(USERNAME)).thenReturn(Optional.of(npc));
        assertEquals(USERNAME, accounts.ensureAccount(USERNAME));
        assertEquals(new BigDecimal("37.80"), npc.getSaldo());
        verify(users, times(1)).save(any(User.class));
    }

    @Test
    void refusesToUseAPreexistingHumanAccount() {
        User human = User.builder().username(USERNAME).build();
        when(users.findByUsername(USERNAME)).thenReturn(Optional.of(human));

        assertThrows(IllegalStateException.class, () -> accounts.ensureAccount(USERNAME));
        verify(users, never()).save(any(User.class));
    }
}
