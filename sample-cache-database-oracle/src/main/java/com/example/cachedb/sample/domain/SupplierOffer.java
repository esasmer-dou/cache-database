package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.*;
import java.math.BigDecimal;

/** Independent supplier quotes: no FK, secondary UNIQUE constraint or cross-row trigger. */
@CacheEntity(table = "sample_supplier_offers", redisNamespace = "sample-supplier-offers",
        indexedColumns = {"supplier", "status", "unit_price", "available_units"},
        independentWriteReordering = true)
@CacheCompositeIndex(columns = {"supplier", "status"})
@CacheNearPolicy(maxEntries = 256, maxBytes = 1_048_576, ttlSeconds = 10, softTtlSeconds = 2)
public record SupplierOffer(
        @CacheId(column = "offer_id") Long offerId,
        @CacheColumn("supplier") String supplier,
        @CacheColumn("status") String status,
        @CacheColumn("unit_price") BigDecimal unitPrice,
        @CacheColumn("available_units") Integer availableUnits) {
    public SupplierOffer {
        if (offerId == null || offerId <= 0 || supplier == null || supplier.isBlank() || supplier.length() > 32
                || !("ACTIVE".equals(status) || "PAUSED".equals(status))
                || unitPrice == null || unitPrice.signum() < 0 || unitPrice.scale() > 4
                || (long) unitPrice.precision() - unitPrice.scale() > 15
                || availableUnits == null || availableUnits < 0 || availableUnits > 1_000_000)
            throw new IllegalArgumentException("Invalid bounded supplier offer");
    }
}
