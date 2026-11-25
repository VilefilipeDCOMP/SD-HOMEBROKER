package br.ufs.simulador_bolsa.orders;

import java.math.BigDecimal;

public record Trade(Order buy, Order sell, long quantity, BigDecimal price) {
}