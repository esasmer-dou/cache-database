package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.queue.SchemaMigrationStep;

import java.time.Instant;
import java.util.List;

public record SchemaMigrationHistoryEntry(
        String operation,
        boolean applied,
        boolean success,
        int stepCount,
        int executedStepCount,
        String failureMessage,
        Instant recordedAt,
        List<SchemaMigrationStep> steps
) {
}
