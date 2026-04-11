package com.reactor.cachedb.starter;

import java.time.Instant;
import java.util.List;

public record SchemaBootstrapResult(
        String mode,
        int processedEntityCount,
        int createdTableCount,
        int validatedTableCount,
        List<String> createdTables,
        List<String> validatedTables,
        List<SchemaBootstrapIssue> issues,
        Instant recordedAt
) {
    public boolean success() {
        return issues == null || issues.isEmpty();
    }
}
