package br.ufs.simulador_bolsa.wallet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;

@ExtendWith(MockitoExtension.class)
class DatabaseWalletServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PortfolioRepository portfolioRepository;

    @Mock
    private AssetRepository assetRepository;

    @InjectMocks
    private DatabaseWalletService walletService;

    private User user;
    private AssetEntity petr4;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L)
                .username("bruno")
                .saldo(new BigDecimal("10000.00"))
                .build();

        petr4 = AssetEntity.builder()
                .id(1L)
                .ticker("PETR4")
                .nomeEmpresa("Petrobras")
                .precoAtual(new BigDecimal("35.00"))
                .build();
    }

    @Test
    @DisplayName("Saldo disponível deve descontar o saldo reservado")
    void testAvailableBalanceDeductsReserved() {
        when(userRepository.findByUsername("bruno")).thenReturn(Optional.of(user));

        BigDecimal initial = walletService.getAvailableBalance("bruno");
        assertEquals(new BigDecimal("10000.00"), initial);

        // Reserva 2.000,00
        boolean ok = walletService.reserveFunds("bruno", new BigDecimal("2000.00"));
        assertTrue(ok);

        BigDecimal availableAfterReserve = walletService.getAvailableBalance("bruno");
        assertEquals(new BigDecimal("8000.00"), availableAfterReserve);
        assertEquals(new BigDecimal("2000.00"), walletService.getReservedBalance("bruno"));

        // Libera 500,00 da reserva
        walletService.releaseFunds("bruno", new BigDecimal("500.00"));
        assertEquals(new BigDecimal("1500.00"), walletService.getReservedBalance("bruno"));
        assertEquals(new BigDecimal("8500.00"), walletService.getAvailableBalance("bruno"));
    }

    @Test
    @DisplayName("Não deve permitir reservar mais fundos do que o disponível")
    void testCannotReserveMoreThanAvailableFunds() {
        when(userRepository.findByUsername("bruno")).thenReturn(Optional.of(user));

        boolean ok = walletService.reserveFunds("bruno", new BigDecimal("15000.00"));
        assertFalse(ok);
        assertEquals(new BigDecimal("10000.00"), walletService.getAvailableBalance("bruno"));
    }

    @Test
    @DisplayName("Ações disponíveis devem descontar as ações reservadas")
    void testAvailableSharesDeductsReservedShares() {
        when(userRepository.findByUsername("bruno")).thenReturn(Optional.of(user));

        PortfolioEntity item = PortfolioEntity.builder()
                .id(new PortfolioId(1L, 1L))
                .usuario(user)
                .ativo(petr4)
                .quantidade(100)
                .precoMedio(new BigDecimal("30.00"))
                .build();

        when(portfolioRepository.findByUsuarioIdAndTicker(1L, "PETR4")).thenReturn(Optional.of(item));

        long available = walletService.getAvailableShares("bruno", "PETR4");
        assertEquals(100, available);

        // Reserva 30 ações para venda
        boolean ok = walletService.reserveShares("bruno", "PETR4", 30);
        assertTrue(ok);

        long availableAfter = walletService.getAvailableShares("bruno", "PETR4");
        assertEquals(70, availableAfter);
        assertEquals(30, walletService.getReservedShares("bruno", "PETR4"));

        // Libera 10 ações
        walletService.releaseShares("bruno", "PETR4", 10);
        assertEquals(20, walletService.getReservedShares("bruno", "PETR4"));
        assertEquals(80, walletService.getAvailableShares("bruno", "PETR4"));
    }

    @Test
    @DisplayName("Deve gerar Snapshot compatível com o contrato do Home Broker")
    void testGetSnapshot() {
        when(userRepository.findByUsername("bruno")).thenReturn(Optional.of(user));

        PortfolioEntity item = PortfolioEntity.builder()
                .id(new PortfolioId(1L, 1L))
                .usuario(user)
                .ativo(petr4)
                .quantidade(50)
                .precoMedio(new BigDecimal("32.50"))
                .build();

        when(portfolioRepository.findByUsuarioIdAndTicker(1L, "PETR4")).thenReturn(Optional.of(item));
        when(portfolioRepository.findByUsuarioIdWithAsset(1L)).thenReturn(List.of(item));

        walletService.reserveFunds("bruno", new BigDecimal("1000.00"));
        walletService.reserveShares("bruno", "PETR4", 10);

        WalletSnapshot snapshot = walletService.getSnapshot("bruno");

        assertEquals(new BigDecimal("9000.00"), snapshot.balance());
        assertEquals(new BigDecimal("1000.00"), snapshot.reservedBalance());
        assertEquals(1, snapshot.positions().size());

        WalletSnapshot.PositionItem pos = snapshot.positions().getFirst();
        assertEquals("PETR4", pos.symbol());
        assertEquals(50, pos.quantity());
        assertEquals(10, pos.reservedQuantity());
        assertEquals(new BigDecimal("32.50"), pos.averagePrice());
    }

    @Test
    @DisplayName("A reconciliação substitui todas as reservas do usuário de uma vez")
    void replaceReservationsReplacesFundsAndShares() {
        when(userRepository.findByUsername("bruno")).thenReturn(Optional.of(user));
        when(portfolioRepository.findByUsuarioIdAndTicker(1L, "PETR4"))
                .thenReturn(Optional.of(PortfolioEntity.builder()
                        .id(new PortfolioId(1L, 1L)).usuario(user).ativo(petr4)
                        .quantidade(100).precoMedio(new BigDecimal("30.00")).build()));

        assertTrue(walletService.reserveFunds("bruno", new BigDecimal("2000.00")));
        assertTrue(walletService.reserveShares("bruno", "PETR4", 30));

        walletService.replaceReservations("bruno", new BigDecimal("500.00"), Map.of("ITUB4", 7L));

        assertEquals(new BigDecimal("500.00"), walletService.getReservedBalance("bruno"));
        assertEquals(0L, walletService.getReservedShares("bruno", "PETR4"));
        assertEquals(7L, walletService.getReservedShares("bruno", "ITUB4"));
    }

    @Test
    @DisplayName("Username numérico nunca deve ser resolvido como ID do banco")
    void numericUsernameIsNotResolvedAsDatabaseId() {
        User numericNamedUser = User.builder()
                .id(7L)
                .username("42")
                .saldo(new BigDecimal("1000.00"))
                .build();

        when(userRepository.findByUsername("42")).thenReturn(Optional.of(numericNamedUser));
        when(portfolioRepository.findByUsuarioIdWithAsset(7L)).thenReturn(List.of());

        assertTrue(walletService.reserveFunds("42", new BigDecimal("100.00")));

        WalletSnapshot snapshot = walletService.getSnapshot("42");
        assertEquals(new BigDecimal("900.00"), snapshot.balance());
        assertEquals(new BigDecimal("100.00"), snapshot.reservedBalance());
        verify(userRepository, never()).findById(anyLong());
    }
}
