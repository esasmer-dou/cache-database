package com.reactor.cachedb.core.queue;

import java.time.Instant;
import java.util.Map;

public record AdminIncident(
        String code,
        String description,
        AdminIncidentSeverity severity,
        Instant detectedAt,
        Map<String, String> fields
) {
}
