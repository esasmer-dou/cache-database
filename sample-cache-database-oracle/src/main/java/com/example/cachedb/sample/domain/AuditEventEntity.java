package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "sample_audit_events", redisNamespace = "sample-audit-events")
public class AuditEventEntity {

    @CacheId(column = "audit_event_id")
    public Long auditEventId;

    @CacheColumn("entity_name")
    public String entityName;

    @CacheColumn("entity_id")
    public Long entityId;

    @CacheColumn("event_type")
    public String eventType;

    @CacheColumn("severity")
    public String severity;

    @CacheColumn("actor")
    public String actor;

    @CacheColumn("created_at")
    public Long createdAt;

    @CacheColumn("message")
    public String message;

    public AuditEventEntity() {
    }
}
