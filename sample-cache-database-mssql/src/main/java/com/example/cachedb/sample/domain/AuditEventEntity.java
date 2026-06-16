package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;

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

    @CacheNamedQuery("securityAuditEvents")
    public static QuerySpec securityAuditEventsQuery(int limit) {
        return QuerySpec.where(QueryFilter.in("severity", List.<Object>of("WARN", "ERROR", "SECURITY")))
                .orderBy(QuerySort.desc("created_at"), QuerySort.desc("audit_event_id"))
                .limitTo(limit);
    }

    @CacheNamedQuery("eventsForEntity")
    public static QuerySpec eventsForEntityQuery(String entityName, long entityId, int limit) {
        return QuerySpec.where(QueryFilter.eq("entity_name", entityName))
                .and(QueryFilter.eq("entity_id", entityId))
                .orderBy(QuerySort.desc("created_at"), QuerySort.desc("audit_event_id"))
                .limitTo(limit);
    }
}
