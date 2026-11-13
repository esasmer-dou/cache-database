package com.reactor.cachedb.spring.data.fixture;

import com.reactor.cachedb.annotations.*;

@CacheEntity(table = "f2_customers", redisNamespace = "f2-customers")
public record Customer(
        @CacheId(column = "id") @CacheGeneratedId(value = CacheGeneratedId.Strategy.SEQUENCE, sequence = "f2_customers") Long id,
        @CacheColumn("tenant_id") @CacheTenantId String tenantId,
        @CacheColumn("display_name") String name,
        @CacheColumn("age") Integer age,
        @CacheColumn("active") Boolean active,
        @CacheColumn("note") String note) {}
