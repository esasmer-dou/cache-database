package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;

public record ScenarioProfileChurnEntry(
        Instant switchedAt,
        String fromProfile,
        String toProfile,
        String pressureLevel,
        long switchCount,
        long usedMemoryBytes,
        long writeBehindBacklog,
        long compactionPendingCount
) {
}
