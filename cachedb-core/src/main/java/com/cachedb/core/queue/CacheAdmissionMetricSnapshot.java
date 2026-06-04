package com.reactor.cachedb.core.queue;

public record CacheAdmissionMetricSnapshot(
        long admittedCount,
        long rejectedCount,
        long evictedCount,
        long lastObservedAtEpochMillis
) {
    public static CacheAdmissionMetricSnapshot empty() {
        return new CacheAdmissionMetricSnapshot(0L, 0L, 0L, 0L);
    }
}
