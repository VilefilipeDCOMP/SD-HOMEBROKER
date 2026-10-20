package br.ufs.simulador_bolsa.market;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;


//Catálogo das ações mantidos em memória
@Component
public class AssetCatalog {

    //concurrentHashMap normal, melhor esse do que o normal pq não precisa de synchronized
    private final Map<String, Asset> assets = new ConcurrentHashMap<>();

    //seta ações iniciais
    public AssetCatalog() {
        add(new Asset("PETR4", "Petrobras PN", new BigDecimal("35.00")));
        add(new Asset("VALE3", "Vale ON", new BigDecimal("68.00")));
        add(new Asset("ITUB4", "Itaú Unibanco PN", new BigDecimal("30.50")));
        add(new Asset("MGLU3", "Magazine Luiza ON", new BigDecimal("2.10")));
    }

    //registra
    private void add(Asset asset) {
        assets.put(asset.symbol(), asset);
    }

    //lista todos
    public List<Asset> listAll() {
        return assets.values().stream()
                .sorted(Comparator.comparing(Asset::symbol))
                .toList();
    }

    //pra achar alguma ação específica, tipo um filtro
    public Optional<Asset> findBySymbol(String symbol) {
        if (symbol == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(assets.get(symbol.trim().toUpperCase(Locale.ROOT)));
    }

    //bolean pra ver se exist
    public boolean exists(String symbol) {
        return findBySymbol(symbol).isPresent();
    }

    //"muda" aquele imutavel, na real ele cria um novo Asset com o mesmo simbolo e o nome, só muda o preco
    public void updatePrice(String symbol, BigDecimal newPrice) {
        assets.computeIfPresent(symbol, (key, old) -> new Asset(old.symbol(), old.name(), newPrice));
    }
}
