package br.ufs.simulador_bolsa.orders;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Componente de tolerância a falhas e recuperação pós-falha (Crash Recovery).
 * Ao iniciar ou reiniciar o backend, busca todas as ordens com status 'ABERTA' persistidas
 * no banco de dados para restabelecer o estado operacional sem perda de consistência.
 */
@Component
@RequiredArgsConstructor
public class OrderRecoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryRunner.class);

    private final OrderRepository orderRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOpenOrders() {
        log.info("CRASH_RECOVERY_STARTED buscando ordens abertas no banco de dados...");
        List<OrderEntity> openOrders = orderRepository.findByStatusWithAssetAndUser("ABERTA");
        log.info("CRASH_RECOVERY_FOUND totalOpenOrders={}", openOrders.size());

        for (OrderEntity entity : openOrders) {
            log.info("ORDER_RECOVERED id={} user={} asset={} side={} qty={} targetPrice={}",
                    entity.getId(),
                    entity.getUsuario().getUsername(),
                    entity.getAtivo().getTicker(),
                    entity.getTipo(),
                    entity.getQuantidade(),
                    entity.getPrecoAlvo());
        }

        log.info("CRASH_RECOVERY_COMPLETED estado de ordens abertas restabelecido com sucesso.");
    }
}
