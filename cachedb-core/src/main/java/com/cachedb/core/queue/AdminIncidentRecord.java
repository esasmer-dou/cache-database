package com.reactor.cachedb.core.queue;

import java.time.Instant;
import java.util.Map;

public record AdminIncidentRecord(
        String entryId,
        String code,
        String description,
        AdminIncidentSeverity severity,
        String source,
        Instant recordedAt,
        Map<String, String> fields
) {
}
