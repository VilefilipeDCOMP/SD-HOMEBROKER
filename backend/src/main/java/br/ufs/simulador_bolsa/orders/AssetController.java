package br.ufs.simulador_bolsa.orders;

import br.ufs.simulador_bolsa.communication.MarketEvent;
import br.ufs.simulador_bolsa.market.Asset;
import br.ufs.simulador_bolsa.market.AssetCatalog;
import br.ufs.simulador_bolsa.market.AssetEntity;
import br.ufs.simulador_bolsa.market.AssetRepository;
import br.ufs.simulador_bolsa.trades.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

//preferi desacoplar o controller das ações
@RequiredArgsConstructor
@Controller
public class AssetController {

    private final AssetCatalog catalog;

    @Autowired(required = false)
    private AssetRepository assetRepository;

    @Autowired(required = false)
    private TradeRepository tradeRepository;

    //pega as ações negociaveis no catálogo sincronizando preço e volume acumulado com o banco
    @MessageMapping("/assets/list")
    @SendToUser(destinations = "/queue/replies", broadcast = false)
    public MarketEvent<List<Asset>> listAssets() {
        sincronizarCatalogoComBanco();
        return new MarketEvent<>("ASSET_LIST", null, UUID.randomUUID(), Instant.now(), catalog.listAll());
    }

    private void sincronizarCatalogoComBanco() {
        if (tradeRepository == null) {
            return;
        }

        Map<String, Long> volumesPorTicker = new HashMap<>();
        for (TradeRepository.AssetVolumeProjection item : tradeRepository.sumVolumeGroupedByTicker()) {
            if (item.getTicker() != null && item.getVolume() != null) {
                volumesPorTicker.put(item.getTicker().trim().toUpperCase(Locale.ROOT), item.getVolume());
            }
        }

        Map<String, AssetEntity> ativosBanco = new HashMap<>();
        if (assetRepository != null) {
            for (AssetEntity entity : assetRepository.findAll()) {
                if (entity.getTicker() != null) {
                    ativosBanco.put(entity.getTicker().trim().toUpperCase(Locale.ROOT), entity);
                }
            }
        }

        for (Asset atual : catalog.listAll()) {
            String ticker = atual.symbol().trim().toUpperCase(Locale.ROOT);
            AssetEntity salvo = ativosBanco.get(ticker);
            long volume = volumesPorTicker.getOrDefault(ticker, 0L);
            catalog.syncMarketData(
                    ticker,
                    atual.name(),
                    salvo != null && salvo.getPrecoAtual() != null ? salvo.getPrecoAtual() : atual.currentPrice(),
                    volume
            );
        }
    }
}
