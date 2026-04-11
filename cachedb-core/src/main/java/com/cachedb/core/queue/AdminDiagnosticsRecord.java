package com.reactor.cachedb.core.queue;

import java.time.Instant;
import java.util.Map;

public record AdminDiagnosticsRecord(
        String entryId,
        String source,
        String note,
        AdminHealthStatus status,
        Instant recordedAt,
        Map<String, String> fields
) {
}
