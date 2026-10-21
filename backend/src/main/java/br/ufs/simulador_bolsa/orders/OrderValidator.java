package br.ufs.simulador_bolsa.orders;

import org.springframework.stereotype.Component;

import br.ufs.simulador_bolsa.market.AssetCatalog;

//como o proprio nome já diz, é o validador dos pedidos neh, rs
//acho que não tem muito o que explicar, é auto explicativo
@Component
public class OrderValidator {

    static final int MAX_CLIENT_ORDER_ID_LENGTH = 64;
    static final long MAX_QUANTITY = 1_000_000L;
    static final int MAX_PRICE_DECIMALS = 2;

    private final AssetCatalog catalog;

    public OrderValidator(AssetCatalog catalog) {
        this.catalog = catalog;
    }

    public ValidationResult validate(OrderRequest request) {
        if (request == null || isBlank(request.userId()) || isBlank(request.clientOrderId())
                || request.clientOrderId().length() > MAX_CLIENT_ORDER_ID_LENGTH
                || isBlank(request.symbol())
                || request.side() == null) {
            return ValidationResult.rejected(RejectReason.INVALID_FIELDS);
        }

        if (request.quantity() == null || request.quantity() <= 0 || request.quantity() > MAX_QUANTITY) {
            return ValidationResult.rejected(RejectReason.INVALID_QUANTITY);
        }

        if (request.price() == null || request.price().signum() <= 0 || request.price().stripTrailingZeros().scale() > MAX_PRICE_DECIMALS) {
            return ValidationResult.rejected(RejectReason.INVALID_PRICE);
        }

        if (!catalog.exists(request.symbol())) {
            return ValidationResult.rejected(RejectReason.UNKNOWN_ASSET);
        }
        return ValidationResult.ok();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
