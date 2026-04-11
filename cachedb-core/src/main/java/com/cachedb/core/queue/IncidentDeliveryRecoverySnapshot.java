package com.reactor.cachedb.core.queue;

public record IncidentDeliveryRecoverySnapshot(
        long replayedCount,
        long failedReplayCount,
        long deadLetterCount,
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
