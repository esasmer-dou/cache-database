package com.reactor.cachedb.starter;

import java.util.List;

/** Bounded application-facing summary of one warm execution. */
public record CacheWarmSummary(
        String operation,
        String planName,
        String routeName,
        String entityName,
        String scope,
        int requestedRows,
        int rowsReadFromSource,
        int rowsSubmittedToRedis,
        long durationMillis,
        CacheWarmTarget target,
        CacheWarmExecutionMode mode,
        List<String> notes
) {
    public CacheWarmSummary {
        operation = requireText(operation, "operation");
        planName = requireText(planName, "planName");
        routeName = requireText(routeName, "routeName");
        entityName = requireText(entityName, "entityName");
        scope = requireText(scope, "scope");
        if (requestedRows <= 0) {
            throw new IllegalArgumentException("requestedRows must be greater than zero");
        }
        if (rowsReadFromSource < 0) {
            throw new IllegalArgumentException("rowsReadFromSource must not be negative");
        }
        if (rowsSubmittedToRedis < 0 || rowsSubmittedToRedis > rowsReadFromSource) {
            throw new IllegalArgumentException("rowsSubmittedToRedis must be between 0 and rowsReadFromSource");
        }
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean dryRun() {
        return mode == CacheWarmExecutionMode.DRY_RUN;
    }

    public boolean projectionOnly() {
        return target == CacheWarmTarget.PROJECTIONS_ONLY;
    }

    public boolean fullySubmitted() {
        return !dryRun() && rowsReadFromSource == rowsSubmittedToRedis;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
