package com.reactor.cachedb.core.queue;

public record LatencyMetricSnapshot(
        long operationCount,
        long averageMicros,
        long p95Micros,
        long p99Micros,
        long maxMicros,
        long lastMicros,
        long lastObservedAtEpochMillis
) {
    public static LatencyMetricSnapshot empty() {
        return new LatencyMetricSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
}
