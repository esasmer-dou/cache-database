package com.reactor.cachedb.redis;

public record RedisHotSetReconciliationResult(
        String nextCursor,
        int inspectedRows,
        int evictedRows,
        int missingRows,
        int invalidRows,
        boolean fullCycleCompleted
) {
    public RedisHotSetReconciliationResult {
        nextCursor = nextCursor == null || nextCursor.isBlank() ? "0" : nextCursor;
        inspectedRows = Math.max(0, inspectedRows);
        evictedRows = Math.max(0, evictedRows);
        missingRows = Math.max(0, missingRows);
        invalidRows = Math.max(0, invalidRows);
    }
}
