package com.reactor.cachedb.core.queue;

public record ProjectionRefreshFailureEntry(
        String entryId,
        String originalEntryId,
        String entityName,
        String projectionName,
        String rawId,
        String operation,
        int attempt,
        long failedAtEpochMillis,
        String errorType,
        String errorMessage,
        String errorRootType,
        String errorRootMessage,
        String errorOrigin
) {
}
