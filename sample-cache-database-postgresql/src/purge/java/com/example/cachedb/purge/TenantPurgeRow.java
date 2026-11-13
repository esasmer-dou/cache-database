package com.example.cachedb.purge;

import com.reactor.cachedb.annotations.*;

@CacheEntity(table = "sample_purge_rows", redisNamespace = "sample-purge-rows",
        tenantPurgeEnabled = true, indexedColumns = {"tenant_id"})
public record TenantPurgeRow(@CacheId(column = "id") Long id,
        @CacheTenantId @CacheColumn("tenant_id") String tenant,
        @CacheColumn("secret") @CacheEncrypted(keyRing = "sample-purge",
                scope = EncryptionScope.REDIS_AND_SOURCE) String secret) {
    public TenantPurgeRow {
        if (id == null || id < 1 || id > 8 || tenant == null
                || !tenant.matches("purge-[0-9a-f]{32}-[ab]") || secret == null
                || !secret.matches("purge-demo-value-[1-8]"))
            throw new IllegalArgumentException("Fixed disposable purge rows only");
    }
    @Override public String toString() { return "TenantPurgeRow[redacted]"; }
}
