package com.reactor.cachedb.spring.boot;

import java.time.Instant;

public record CacheScheduledWarmSnapshot(
        String jobName,
        CacheScheduledWarmState state,
        String instanceId,
        long executionCount,
        long skippedCount,
        long failureCount,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        long lastDurationMillis,
        int lastLoadedRows,
        int lastSubmittedRows,
        int lastInspectedRows,
        int lastEvictedRows,
        int lastMissingRows,
        int lastInvalidRows,
        String lastReconciliationCursor,
        boolean lastReconciliationCycleCompleted,
        String detail
) {
    public CacheScheduledWarmSnapshot {
        jobName = jobName == null ? "" : jobName;
        state = state == null ? CacheScheduledWarmState.REGISTERED : state;
        instanceId = instanceId == null ? "" : instanceId;
        lastReconciliationCursor = lastReconciliationCursor == null || lastReconciliationCursor.isBlank()
                ? "0"
                : lastReconciliationCursor;
        detail = detail == null ? "" : detail;
    }
}
