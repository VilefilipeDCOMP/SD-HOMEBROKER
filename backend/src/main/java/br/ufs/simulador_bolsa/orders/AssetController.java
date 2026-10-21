package br.ufs.simulador_bolsa.orders;

import br.ufs.simulador_bolsa.communication.MarketEvent;
import br.ufs.simulador_bolsa.market.Asset;
import br.ufs.simulador_bolsa.market.AssetCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

//preferi desacoplar o controller das ações
@RequiredArgsConstructor
@Controller
public class AssetController {

    private final AssetCatalog catalog;

    //pega as ações negociaveis no catálogo(lista)
    @MessageMapping("/assets/list")
    @SendToUser(destinations = "/queue/replies", broadcast = false)
    public MarketEvent<List<Asset>> listAssets() {
        return new MarketEvent<>("ASSET_LIST", null, UUID.randomUUID(), Instant.now(), catalog.listAll());
    }
}
