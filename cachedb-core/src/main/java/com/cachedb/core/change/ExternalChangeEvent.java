package com.reactor.cachedb.core.change;

import java.time.Instant;
import java.util.Map;

public record ExternalChangeEvent(
        String entityName,
        Object id,
        ExternalChangeType type,
        Map<String, Object> columns,
        long version,
        Instant occurredAt,
        String source
) {
    public ExternalChangeEvent {
        entityName = entityName == null ? "" : entityName.trim();
        type = type == null ? ExternalChangeType.UPSERT : type;
        columns = columns == null ? Map.of() : Map.copyOf(columns);
        version = Math.max(0L, version);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        source = source == null ? "" : source.trim();
    }
}
