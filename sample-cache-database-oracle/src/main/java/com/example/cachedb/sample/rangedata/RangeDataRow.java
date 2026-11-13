package com.example.cachedb.sample.rangedata;

import com.reactor.cachedb.annotations.*;

@CacheEntity(table = "sample_range_rows", redisNamespace = "sample-range-rows",
        indexedColumns = {"tenant_id", "run_id", "status", "rank_value"})
@CacheCompositeIndex(name = "tenant_run_rank", columns = {"tenant_id", "run_id", "status"},
        sortBy = "rank_value")
public record RangeDataRow(
        @CacheId(column = "row_id") String rowId,
        @CacheTenantId @CacheColumn("tenant_id") String tenantId,
        @CacheColumn("run_id") String runId,
        @CacheColumn("status") String status,
        @CacheColumn("rank_value") Long rankValue) {
    private static final java.util.regex.Pattern TENANT = java.util.regex.Pattern.compile("shop-[ab]");
    private static final java.util.regex.Pattern RUN = java.util.regex.Pattern.compile("[a-z0-9]{1,32}");
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("shop-[ab]:[a-z0-9]{1,32}:[a-z0-9]{1,8}");
    public RangeDataRow {
        if (tenantId == null || !TENANT.matcher(tenantId).matches()
                || runId == null || !RUN.matcher(runId).matches()
                || rowId == null || !ID.matcher(rowId).matches() || !rowId.startsWith(tenantId + ":" + runId + ":")
                || !("OPEN".equals(status) || "CLOSED".equals(status)) || rankValue == null)
            throw new IllegalArgumentException("Invalid bounded range row");
    }
}
