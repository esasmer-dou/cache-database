package com.reactor.cachedb.core.route;

public record TenantCacheQuota(
        String tenantColumn,
        long maxHotRows,
        long memoryBudgetBytes,
        boolean evictOnBreach
) {
    public TenantCacheQuota {
        tenantColumn = blankToNull(tenantColumn);
        maxHotRows = Math.max(0L, maxHotRows);
        memoryBudgetBytes = Math.max(0L, memoryBudgetBytes);
    }

    public static TenantCacheQuota unbounded() {
        return new TenantCacheQuota(null, 0L, 0L, true);
    }

    public boolean bounded() {
        return tenantColumn != null && (maxHotRows > 0L || memoryBudgetBytes > 0L);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
