package com.reactor.cachedb.core.queue;

import java.time.Instant;
import java.util.Map;

public record ReconciliationRecord(
        String entryId,
        String status,
        String entityName,
        String operationType,
        String entityId,
        long version,
        Instant reconciledAt,
        WriteFailureDetails replayFailureDetails,
        Map<String, String> fields
) {
}
