package com.reactor.cachedb.core.queue;

import java.time.Instant;

public record RedisRuntimeProfileEvent(
        String fromProfile,
        String toProfile,
        String pressureLevel,
        Instant switchedAt,
        long switchCount,
        long usedMemoryBytes,
        long writeBehindBacklog,
        long compactionPendingCount
) {
}
