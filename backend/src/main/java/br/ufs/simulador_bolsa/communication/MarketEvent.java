package br.ufs.simulador_bolsa.communication;

import java.time.Instant;
import java.util.UUID;

public record MarketEvent<T>(
        String type,
        String requestId,
        UUID eventId,
        Instant timestamp,
        T data
) {
}