package com.reactor.cachedb.starter;

import java.util.List;

public record CacheWarmResult(
        String planName,
        String entityName,
        int loadedRows,
        int submittedRows,
        long durationMillis,
        boolean forceImmediateProjectionRefresh,
        boolean reindexQueryIndexes,
        List<String> notes
) {
    public CacheWarmResult {
        if (loadedRows < 0) {
            throw new IllegalArgumentException("loadedRows must not be negative");
        }
        if (submittedRows < 0 || submittedRows > loadedRows) {
            throw new IllegalArgumentException("submittedRows must be between 0 and loadedRows");
        }
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
