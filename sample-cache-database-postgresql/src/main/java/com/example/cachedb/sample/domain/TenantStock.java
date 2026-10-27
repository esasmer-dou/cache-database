package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.*;

/** Advisory warehouse availability, not an inventory reservation or payment ledger. */
@CacheEntity(table = "sample_tenant_stock", redisNamespace = "sample-tenant-stock",
        indexedColumns = {"tenant_id", "warehouse", "status", "available_units"})
@CacheCompositeIndex(columns = {"tenant_id", "status"})
@CachePartitionedIndex(partitionBy = "warehouse", sortBy = "available_units")
@CacheNearPolicy(maxEntries = 256, maxBytes = 1_048_576, ttlSeconds = 10)
public record TenantStock(
        @CacheId(column = "stock_id") Long stockId,
        @CacheTenantId @CacheColumn("tenant_id") String tenantId,
        @CacheColumn("warehouse") String warehouse,
        @CacheColumn("status") String status,
        @CacheColumn("available_units") Integer availableUnits) {
    public TenantStock {
        if (stockId == null || stockId <= 0 || tenantId == null || tenantId.isBlank() || tenantId.length() > 32
                || warehouse == null || warehouse.isBlank() || warehouse.length() > 32
                || !("AVAILABLE".equals(status) || "HELD".equals(status))
                || availableUnits == null || availableUnits < 0 || availableUnits > 1_000_000)
            throw new IllegalArgumentException("Invalid bounded tenant stock");
    }
}
