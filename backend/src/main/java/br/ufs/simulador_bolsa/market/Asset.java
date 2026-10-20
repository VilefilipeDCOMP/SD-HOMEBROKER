package br.ufs.simulador_bolsa.market;

import java.math.BigDecimal;

/**
 * é a ação negociavel de uma empresa
 * coloquei BigDecimal pq se for double ele vai arredondar
 * record é pra deixar imutável, só o que vai "mudar" isso vai ser no AssetCatalog.updatePrice
 * o symblo eu setei para ser aqueles códigos de tipo petr4, pensei em setar como "code" ou algo assim, mas achei que ficaria confuso
 */
public record Asset(String symbol, String name, BigDecimal currentPrice) {
}
