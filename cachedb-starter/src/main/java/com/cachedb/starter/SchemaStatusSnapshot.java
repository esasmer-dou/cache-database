package com.reactor.cachedb.starter;

import java.time.Instant;

public record SchemaStatusSnapshot(
        String bootstrapMode,
        boolean autoApplyOnStart,
        boolean includeVersionColumn,
        boolean includeDeletedColumn,
        String schemaName,
        boolean validationSucceeded,
        int validationIssueCount,
        int processedEntityCount,
        int validatedTableCount,
        int migrationStepCount,
        int createTableStepCount,
        int addColumnStepCount,
        int ddlEntityCount,
        Instant recordedAt
) {
}
