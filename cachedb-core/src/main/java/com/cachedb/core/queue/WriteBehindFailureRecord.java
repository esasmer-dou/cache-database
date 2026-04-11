package com.reactor.cachedb.core.queue;

import java.time.Instant;
import java.util.Map;

public record WriteBehindFailureRecord(
        QueuedWriteOperation operation,
        String errorType,
        String errorMessage,
        WriteFailureDetails failureDetails,
        Instant failedAt,
        int attempts,
        Map<String, String> originalEvent
) {
}
