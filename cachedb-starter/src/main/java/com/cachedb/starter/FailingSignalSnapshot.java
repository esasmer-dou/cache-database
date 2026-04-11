package com.reactor.cachedb.starter;

import java.time.Instant;

public record FailingSignalSnapshot(
        String signalCode,
        String severity,
        long activeCount,
        long recentCount,
        String summary,
        Instant lastSeenAt
) {
}
