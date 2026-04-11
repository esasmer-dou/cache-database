package com.reactor.cachedb.core.queue;

public record ProjectionRefreshSnapshot(
        long streamLength,
        long deadLetterStreamLength,
        long processedCount,
        long retriedCount,
        long failedCount,
        long replayedCount,
        long claimedCount,
        long pendingCount,
        long lagEstimateMillis,
        boolean backlogPresent,
        long lastProcessedAtEpochMillis,
        long lastErrorAtEpochMillis,
        String lastErrorType,
        String lastErrorMessage,
        String lastErrorRootType,
        String lastErrorRootMessage,
        String lastErrorOrigin,
        String lastPoisonEntryId
) {
    public static ProjectionRefreshSnapshot empty() {
        return new ProjectionRefreshSnapshot(
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                false,
                0L,
                0L,
                "",
                "",
                "",
                "",
                "",
                ""
        );
    }
}
