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

    //"muda" aquele imutavel, na real ele cria um novo Asset mantendo simbolo, nome e volume, só muda o preco
    public void updatePrice(String symbol, BigDecimal newPrice) {
        if (symbol == null || newPrice == null) {
            return;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        assets.computeIfPresent(normalized, (key, old) -> new Asset(old.symbol(), old.name(), newPrice, old.volume()));
    }

    //atualiza o preco e acumula a quantidade negociada no volume do ativo
    public long recordTrade(String symbol, BigDecimal newPrice, long quantity) {
        if (symbol == null) {
            return 0L;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        Asset updated = assets.computeIfPresent(normalized, (key, old) -> new Asset(
                old.symbol(),
                old.name(),
                newPrice != null ? newPrice : old.currentPrice(),
                old.volume() + Math.max(0L, quantity)
        ));
        return updated != null ? updated.volume() : Math.max(0L, quantity);
    }

    //sincroniza preco e volume em memoria com os valores consolidados no banco
    public void syncMarketData(String symbol, String name, BigDecimal currentPrice, long volume) {
        if (symbol == null) {
            return;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        assets.compute(normalized, (key, old) -> {
            String resolvedName = (name != null && !name.isBlank())
                    ? name
                    : (old != null ? old.name() : normalized);
            BigDecimal resolvedPrice = currentPrice != null
                    ? currentPrice
                    : (old != null ? old.currentPrice() : BigDecimal.ZERO);
            return new Asset(normalized, resolvedName, resolvedPrice, Math.max(0L, volume));
        });
    }
}
