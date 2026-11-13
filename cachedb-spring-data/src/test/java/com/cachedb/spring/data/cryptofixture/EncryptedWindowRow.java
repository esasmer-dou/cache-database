package com.reactor.cachedb.spring.data.cryptofixture;

import com.reactor.cachedb.annotations.*;

@CacheEntity(table="encrypted_window_rows",redisNamespace="encrypted_windows",indexedColumns={"id","tenant_id","ranking"})
public record EncryptedWindowRow(@CacheId(column="id") Long id,
        @CacheTenantId @CacheColumn("tenant_id") String tenant,
        @CacheColumn("ranking") int ranking,
        @CacheEncrypted(blindIndex=true) @CacheColumn("contact") String contact) {}
