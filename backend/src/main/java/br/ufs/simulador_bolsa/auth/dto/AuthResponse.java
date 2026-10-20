package br.ufs.simulador_bolsa.auth.dto;

import java.math.BigDecimal;
import java.util.Map;

public record AuthResponse(
    String type,
    String token,
    Long userId,
    String username,
    BigDecimal balance,
    Map<String, Integer> portfolio
) {}
