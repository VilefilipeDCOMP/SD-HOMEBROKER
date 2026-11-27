package br.ufs.simulador_bolsa.wallet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.interceptor.StompPrincipal;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.communication.MarketEvent;
import br.ufs.simulador_bolsa.orders.CurrentUserResolver;
import br.ufs.simulador_bolsa.trades.TradeEntity;
import br.ufs.simulador_bolsa.trades.TradeItemResponse;
import br.ufs.simulador_bolsa.trades.TradeRepository;

/** Regressão: /trades/mine não troca username numérico por ID do banco. */
class WalletIdentityRegressionTest {

    @Test
    void numericUsernameQueriesTradesForTheAuthenticatedAccount() {
        DatabaseWalletService wallet = mock(DatabaseWalletService.class);
        CurrentUserResolver users = mock(CurrentUserResolver.class);
        UserRepository userRepository = mock(UserRepository.class);
        TradeRepository tradeRepository = mock(TradeRepository.class);

        User authenticated = User.builder().id(7L).username("42")
                .saldo(new BigDecimal("1000.00")).build();
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setUser(new StompPrincipal(authenticated));

        when(users.resolve(headers)).thenReturn("42");
        when(userRepository.findByUsername("42")).thenReturn(Optional.of(authenticated));
        when(tradeRepository.findByUsuarioId(7L)).thenReturn(List.<TradeEntity>of());

        WalletController controller = new WalletController(wallet, users, userRepository, tradeRepository);
        MarketEvent<List<TradeItemResponse>> event = controller.myTrades(headers);

        assertTrue(event.data().isEmpty());
        verify(tradeRepository).findByUsuarioId(7L);
        verify(userRepository, never()).findById(anyLong());
    }
}
