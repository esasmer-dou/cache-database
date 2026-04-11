package com.reactor.cachedb.examples.entity;

import com.reactor.cachedb.annotations.CacheCodec;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.examples.codec.TagValueCodec;

import java.time.Instant;

@CacheEntity(table = "cachedb_example_audit_events", redisNamespace = "audit-events")
public class AuditEventEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("account_state")
    public AccountState accountState;

    @CacheColumn("created_at")
    public Instant createdAt;

    @CacheColumn("tag_value")
    @CacheCodec(TagValueCodec.class)
    public TagValue tagValue;

    public AuditEventEntity() {
    }
}
