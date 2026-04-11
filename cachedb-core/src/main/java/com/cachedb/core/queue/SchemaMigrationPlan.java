package com.reactor.cachedb.core.queue;

import java.time.Instant;
import java.util.List;

public record SchemaMigrationPlan(
        int tableCount,
        int stepCount,
        List<SchemaMigrationStep> steps,
        Instant recordedAt
) {
    public boolean empty() {
        return steps == null || steps.isEmpty();
    }
}
