package br.ufs.simulador_bolsa.orders;

public interface SettlementPort {
    void settle(Trade trade);
}