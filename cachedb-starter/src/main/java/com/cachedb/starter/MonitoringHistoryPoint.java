package com.reactor.cachedb.starter;

import java.time.Instant;

public record MonitoringHistoryPoint(
        Instant recordedAt,
        long writeBehindBacklog,
        long deadLetterBacklog,
        long redisUsedMemoryBytes,
        long compactionPendingCount,
        String runtimeProfile,
        String healthStatus
) {
}
