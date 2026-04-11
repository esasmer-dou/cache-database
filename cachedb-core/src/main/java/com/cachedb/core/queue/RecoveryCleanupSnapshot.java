package com.reactor.cachedb.core.queue;

public record RecoveryCleanupSnapshot(
        long runCount,
        long prunedDeadLetterCount,
        long prunedReconciliationCount,
        long prunedArchiveCount,
        long lastRunAtEpochMillis,
        long lastErrorAtEpochMillis,
        String lastErrorType,
        String lastErrorMessage,
        String lastErrorRootType,
        String lastErrorRootMessage,
        String lastErrorOrigin,
        String lastErrorStackTrace
) {
}
