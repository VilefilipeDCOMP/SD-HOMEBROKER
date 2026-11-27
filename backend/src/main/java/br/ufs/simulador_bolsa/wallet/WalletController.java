package br.ufs.simulador_bolsa.wallet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import br.ufs.simulador_bolsa.auth.entity.User;
import br.ufs.simulador_bolsa.auth.repository.UserRepository;
import br.ufs.simulador_bolsa.communication.MarketEvent;
import br.ufs.simulador_bolsa.orders.CurrentUserResolver;
import br.ufs.simulador_bolsa.trades.TradeEntity;
import br.ufs.simulador_bolsa.trades.TradeItemResponse;
import br.ufs.simulador_bolsa.trades.TradeRepository;
import lombok.RequiredArgsConstructor;

/**
 * Controller STOMP responsável pelos endpoints de consulta de carteira e histórico de negociações.
 * Garante que cada usuário consulte exclusivamente a sua própria carteira e os seus próprios trades.
 */
@Controller
@RequiredArgsConstructor
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final DatabaseWalletService walletService;
    private final CurrentUserResolver users;
    private final UserRepository userRepository;
    private final TradeRepository tradeRepository;

    /**
     * Snapshot da carteira do próprio usuário autenticado.
     * Contrato do Home Broker: destino /app/wallet/me -> resposta em /user/queue/replies
     * tipo "WALLET_SNAPSHOT"
     */
    @MessageMapping("/wallet/me")
    @SendToUser(destinations = "/queue/replies", broadcast = false)
    public MarketEvent<WalletSnapshot> myWallet(SimpMessageHeaderAccessor headers) {
        String username = users.resolve(headers);
        log.info("WALLET_QUERY_REQUESTED username={}", username);

        WalletSnapshot snapshot = walletService.getSnapshot(username);

        return new MarketEvent<>(
                "WALLET_SNAPSHOT",
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                Instant.now(),
                snapshot
        );
    }

    /**
     * Histórico de negociações (trades) do próprio usuário autenticado.
     * Contrato do Home Broker: destino /app/trades/mine -> resposta em /user/queue/replies
     * tipo "TRADE_HISTORY"
     */
    @MessageMapping("/trades/mine")
    @SendToUser(destinations = "/queue/replies", broadcast = false)
    public MarketEvent<List<TradeItemResponse>> myTrades(SimpMessageHeaderAccessor headers) {
        String username = users.resolve(headers);
        log.info("TRADE_HISTORY_REQUESTED username={}", username);

        User user = userRepository.findByUsername(username).orElse(null);

        if (user == null) {
            return new MarketEvent<>(
                    "TRADE_HISTORY",
                    UUID.randomUUID().toString(),
                    UUID.randomUUID(),
                    Instant.now(),
                    List.of()
            );
        }

        final Long currentUserId = user.getId();
        List<TradeEntity> trades = tradeRepository.findByUsuarioId(currentUserId);

        List<TradeItemResponse> items = trades.stream().map(t -> {
            boolean isBuyer = t.getOrdemCompra().getUsuario().getId().equals(currentUserId);
            String side = isBuyer ? "BUY" : "SELL";
            Long orderId = isBuyer ? t.getOrdemCompra().getId() : t.getOrdemVenda().getId();

            return new TradeItemResponse(
                    String.valueOf(t.getId()),
                    String.valueOf(orderId),
                    t.getAtivo().getTicker(),
                    side,
                    t.getQuantidadeExecutada(),
                    t.getPrecoExecutado(),
                    t.getExecutadoEm()
            );
        }).toList();

        return new MarketEvent<>(
                "TRADE_HISTORY",
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                Instant.now(),
                items
        );
    }
}
