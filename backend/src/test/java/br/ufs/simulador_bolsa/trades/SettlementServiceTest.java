package br.ufs.simulador_bolsa.trades;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.orders.OrderEntity;
import br.ufs.simulador_bolsa.orders.OrderRepository;
import br.ufs.simulador_bolsa.wallet.DatabaseWalletService;
import br.ufs.simulador_bolsa.wallet.PortfolioEntity;
import br.ufs.simulador_bolsa.wallet.PortfolioRepository;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private PortfolioRepository portfolioRepository;

    @Mock
    private TradeRepository tradeRepository;

    @Mock
    private DatabaseWalletService walletService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private SettlementService settlementService;

    private User buyer;
    private User seller;
    private AssetEntity petr4;
    private OrderEntity buyOrder;
    private OrderEntity sellOrder;

    @BeforeEach
    void setUp() {
        buyer = User.builder()
                .id(1L)
                .username("comprador")
                .saldo(new BigDecimal("10000.00"))
                .build();

        seller = User.builder()
                .id(2L)
                .username("vendedor")
                .saldo(new BigDecimal("5000.00"))
                .build();

        petr4 = AssetEntity.builder()
                .id(10L)
                .ticker("PETR4")
                .nomeEmpresa("Petrobras")
                .precoAtual(new BigDecimal("35.00"))
                .build();

        buyOrder = OrderEntity.builder()
                .id(100L)
                .usuario(buyer)
                .ativo(petr4)
                .tipo("COMPRA")
                .quantidade(10)
                .precoAlvo(new BigDecimal("36.00")) // comprador aceitava até 36.00
                .status("ABERTA")
                .build();

        sellOrder = OrderEntity.builder()
                .id(200L)
                .usuario(seller)
                .ativo(petr4)
                .tipo("VENDA")
                .quantidade(10)
                .precoAlvo(new BigDecimal("35.00"))
                .status("ABERTA")
                .build();
    }

    @Test
    @DisplayName("Liquidação atômica deve transferir saldo, ações, devolver sobra e calcular preço médio ponderado")
    void testAtomicSettlementAndSurplusRefund() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(200L)).thenReturn(Optional.of(sellOrder));
        when(userRepository.findById(1L)).thenReturn(Optional.of(buyer));
        when(userRepository.findById(2L)).thenReturn(Optional.of(seller));
        when(assetRepository.findByTicker("PETR4")).thenReturn(Optional.of(petr4));

        PortfolioEntity sellerPortfolio = PortfolioEntity.builder()
                .usuario(seller)
                .ativo(petr4)
                .quantidade(20)
                .precoMedio(new BigDecimal("30.00"))
                .build();
        when(portfolioRepository.findByUsuarioIdAndTicker(2L, "PETR4")).thenReturn(Optional.of(sellerPortfolio));

        // Comprador já tinha 10 cotas a 30.00 cada (total 300)
        PortfolioEntity buyerPortfolio = PortfolioEntity.builder()
                .usuario(buyer)
                .ativo(petr4)
                .quantidade(10)
                .precoMedio(new BigDecimal("30.00"))
                .build();
        when(portfolioRepository.findByUsuarioIdAndTicker(1L, "PETR4")).thenReturn(Optional.of(buyerPortfolio));

        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Casamento de 10 ações executado a 34.00 (abaixo do alvo de 36.00 do comprador)
        TradeExecutionCommand cmd = new TradeExecutionCommand(
                100L,
                200L,
                "PETR4",
                10,
                new BigDecimal("34.00")
        );

        TradeEntity trade = settlementService.settleTrade(cmd);

        assertNotNull(trade);
        assertEquals(10, trade.getQuantidadeExecutada());
        assertEquals(new BigDecimal("34.00"), trade.getPrecoExecutado());

        // Financeiro: 10 x 34.00 = 340.00
        // Comprador: 10000 - 340 = 9660.00
        assertEquals(new BigDecimal("9660.00"), buyer.getSaldo());
        // Vendedor: 5000 + 340 = 5340.00
        assertEquals(new BigDecimal("5340.00"), seller.getSaldo());

        // Devolução da reserva de sobra:
        // O comprador reservou 10 * 36.00 = 360.00
        verify(walletService).releaseFunds("1", new BigDecimal("360.00"));

        // Custódia vendedor: tinha 20, vendeu 10 -> sobram 10
        assertEquals(10, sellerPortfolio.getQuantidade());
        verify(walletService).releaseShares("2", "PETR4", 10);

        // Preço Médio ponderado do comprador:
        // Antigo: 10 @ 30.00 = 300.00
        // Novo: 10 @ 34.00 = 340.00
        // Total: 20 cotas por 640.00 -> PM = 32.00
        assertEquals(20, buyerPortfolio.getQuantidade());
        assertEquals(new BigDecimal("32.00"), buyerPortfolio.getPrecoMedio());

        // Ordens marcadas como EXECUTADA
        assertEquals("EXECUTADA", buyOrder.getStatus());
        assertEquals("EXECUTADA", sellOrder.getStatus());
    }

    @Test
    @DisplayName("Autonegociação deve ser rejeitada para o mesmo investidor")
    void testSelfTradeIsRejected() {
        sellOrder.setUsuario(buyer); // vendedor é a mesma pessoa que o comprador

        when(orderRepository.findById(100L)).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(200L)).thenReturn(Optional.of(sellOrder));

        TradeExecutionCommand cmd = new TradeExecutionCommand(100L, 200L, "PETR4", 5, new BigDecimal("35.00"));

        assertThrows(IllegalStateException.class, () -> settlementService.settleTrade(cmd));
        verifyNoInteractions(tradeRepository);
    }
}
