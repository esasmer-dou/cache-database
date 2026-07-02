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
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
