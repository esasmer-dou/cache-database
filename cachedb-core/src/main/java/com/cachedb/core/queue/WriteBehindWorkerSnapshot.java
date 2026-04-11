package com.reactor.cachedb.core.queue;

public record WriteBehindWorkerSnapshot(
        long flushedCount,
        long batchFlushCount,
        long batchFlushedOperationCount,
        long lastObservedBacklog,
        int lastAdaptiveBatchSize,
        long coalescedCount,
        int lastInFlightFlushGroups,
        long staleSkippedCount,
        long retriedCount,
        long deadLetterCount,
        long claimedCount,
        long pendingRecoveryCount,
        long lastErrorAtEpochMillis,
        String lastErrorType,
        String lastErrorMessage,
        String lastErrorRootType,
        String lastErrorRootMessage,
        String lastErrorOrigin,
        String lastErrorStackTrace
) {
}
