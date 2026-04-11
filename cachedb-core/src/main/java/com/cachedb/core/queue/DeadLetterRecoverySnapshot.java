package com.reactor.cachedb.core.queue;

public record DeadLetterRecoverySnapshot(
        long replayedCount,
        long staleSkippedCount,
        long failedCount,
        long claimedCount,
        long lastErrorAtEpochMillis,
        String lastErrorType,
        String lastErrorMessage,
        String lastErrorRootType,
        String lastErrorRootMessage,
        String lastErrorOrigin,
        String lastErrorStackTrace
) {
}
