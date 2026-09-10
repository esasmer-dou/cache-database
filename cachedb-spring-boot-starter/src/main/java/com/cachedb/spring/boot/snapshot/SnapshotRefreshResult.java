package com.reactor.cachedb.spring.boot.snapshot;

/** Completion means the whole new generation is active, not merely submitted. */
public record SnapshotRefreshResult(
        String status, int loadedRows, int submittedRows, long durationMillis) {
    static SnapshotRefreshResult skipped(String status) {
        return new SnapshotRefreshResult(status, 0, 0, 0);
    }
}
