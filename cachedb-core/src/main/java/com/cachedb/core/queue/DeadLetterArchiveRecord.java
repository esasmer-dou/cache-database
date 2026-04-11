package com.reactor.cachedb.core.queue;

import java.time.Instant;
import java.util.Map;

public record DeadLetterArchiveRecord(
        String entryId,
        String sourceEntryId,
        String status,
        String entityName,
        String operationType,
        String entityId,
        long version,
        Instant archivedAt,
        Map<String, String> fields
) {
}
