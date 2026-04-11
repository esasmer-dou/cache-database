package com.reactor.cachedb.core.queue;

public record DeadLetterReplayPreview(
        String entryId,
        boolean exists,
        boolean replayEligible,
        boolean stale,
        String predictedStatus,
        String reason
) {
}
