package com.reactor.cachedb.core.queue;

public record RedisGuardrailSnapshot(
        long usedMemoryBytes,
        long usedMemoryPeakBytes,
        long maxMemoryBytes,
        long writeBehindBacklog,
        long compactionPendingCount,
        long compactionPayloadCount,
        long hardRejectedWriteCount,
        long producerHighPressureDelayCount,
        long producerCriticalPressureDelayCount,
        long lastSampleAtEpochMillis,
        String pressureLevel
) {
}
