package br.ufs.simulador_bolsa.simulation;

public record EconomicEvent(String title, String message, String symbol, int impactPercent) {
}
