package com.reactor.cachedb.core.queue;

import java.time.Instant;
import java.util.Map;

public record DeadLetterEntry(
        String entryId,
        QueuedWriteOperation operation,
        String errorType,
        String errorMessage,
        WriteFailureDetails failureDetails,
        int deadLetterAttempts,
        Instant createdAt,
        Map<String, String> fields
) {
}
