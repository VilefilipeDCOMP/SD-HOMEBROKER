package br.ufs.simulador_bolsa.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import br.ufs.simulador_bolsa.market.AssetCatalog;

class OrderValidatorTest {

    private final OrderValidator validator = new OrderValidator(new AssetCatalog());

    private OrderRequest request(String clientId, String symbol, OrderSide side, Long qty, String price) {
        return new OrderRequest("u1", clientId, symbol, side, qty, price == null ? null : new BigDecimal(price));
    }

    @Test
    void validOrderPasses() {
        assertTrue(validator.validate(request("c1", "PETR4", OrderSide.BUY, 10L, "35.00")).valid());
    }

    @Test
    void lowercaseSymbolIsNormalized() {
        assertTrue(validator.validate(request("c1", " petr4 ", OrderSide.BUY, 10L, "35.00")).valid());
    }

    @Test
    void nullRequestIsRejected() {
        assertEquals(RejectReason.INVALID_FIELDS, validator.validate(null).reason());
    }

    @Test
    void blankClientOrderIdIsRejected() {
        assertEquals(RejectReason.INVALID_FIELDS,
                validator.validate(request("  ", "PETR4", OrderSide.BUY, 1L, "35.00")).reason());
    }

    @Test
    void missingSideIsRejected() {
        assertEquals(RejectReason.INVALID_FIELDS,
                validator.validate(request("c1", "PETR4", null, 1L, "35.00")).reason());
    }

    @Test
    void zeroNegativeOrMissingQuantityIsRejected() {
        assertEquals(RejectReason.INVALID_QUANTITY,
                validator.validate(request("c1", "PETR4", OrderSide.BUY, 0L, "35.00")).reason());
        assertEquals(RejectReason.INVALID_QUANTITY,
                validator.validate(request("c1", "PETR4", OrderSide.BUY, -5L, "35.00")).reason());
        assertEquals(RejectReason.INVALID_QUANTITY,
                validator.validate(request("c1", "PETR4", OrderSide.BUY, null, "35.00")).reason());
    }

    @Test
    void absurdQuantityIsRejected() {
        assertEquals(RejectReason.INVALID_QUANTITY,
                validator.validate(request("c1", "PETR4", OrderSide.BUY, 1_000_001L, "35.00")).reason());
    }

    @Test
    void zeroNegativeMissingOrTooPreciseOrPriceIsRejected() {
        assertEquals(RejectReason.INVALID_PRICE,
                validator.validate(request("c1", "PETR4", OrderSide.BUY, 1L, "0")).reason());
        assertEquals(RejectReason.INVALID_PRICE,
                validator.validate(request("c1", "PETR4", OrderSide.BUY, 1L, "-1.00")).reason());
        assertEquals(RejectReason.INVALID_PRICE,
                validator.validate(request("c1", "PETR4", OrderSide.BUY, 1L, null)).reason());
        assertEquals(RejectReason.INVALID_PRICE,
                validator.validate(request("c1", "PETR4", OrderSide.BUY, 1L, "35.123")).reason());
    }

    @Test
    void priceWithTrailingZerosIsAccepted() {
        assertTrue(validator.validate(request("c1", "PETR4", OrderSide.BUY, 1L, "35.1000")).valid());
    }

    @Test
    void unknownAssetIsRejected() {
        assertEquals(RejectReason.UNKNOWN_ASSET,
                validator.validate(request("c1", "XXXX9", OrderSide.BUY, 1L, "35.00")).reason());
    }
}
