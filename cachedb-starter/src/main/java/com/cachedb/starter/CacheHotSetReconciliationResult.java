package com.reactor.cachedb.starter;

public record CacheHotSetReconciliationResult(
        String entityName,
        String nextCursor,
        int inspectedRows,
        int evictedRows,
        int missingRows,
        int invalidRows,
        boolean fullCycleCompleted
) {
    public CacheHotSetReconciliationResult {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName must not be blank");
        }
        nextCursor = nextCursor == null || nextCursor.isBlank() ? "0" : nextCursor;
        inspectedRows = Math.max(0, inspectedRows);
        evictedRows = Math.max(0, evictedRows);
        missingRows = Math.max(0, missingRows);
        invalidRows = Math.max(0, invalidRows);
    }
}
