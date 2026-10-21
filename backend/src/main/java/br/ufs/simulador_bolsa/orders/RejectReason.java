package br.ufs.simulador_bolsa.orders;

//são os "exceptions" que vamos devolver ao usuario
public enum RejectReason {
    INVALID_FIELDS,
    INVALID_QUANTITY,
    INVALID_PRICE,
    UNKNOWN_ASSET,
    INSUFFICIENT_FUNDS,
    INSUFFICIENT_SHARES,
    SYSTEM_BUSY,
    INTERNAL_ERROR
}
