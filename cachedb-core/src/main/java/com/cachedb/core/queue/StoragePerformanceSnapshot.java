package com.reactor.cachedb.core.queue;

import java.util.Map;

public record StoragePerformanceSnapshot(
        LatencyMetricSnapshot redisRead,
        LatencyMetricSnapshot redisWrite,
        LatencyMetricSnapshot postgresRead,
        LatencyMetricSnapshot postgresWrite,
        Map<String, LatencyMetricSnapshot> redisReadBreakdown,
        Map<String, LatencyMetricSnapshot> redisWriteBreakdown,
        Map<String, LatencyMetricSnapshot> postgresReadBreakdown,
        Map<String, LatencyMetricSnapshot> postgresWriteBreakdown
) {
    public StoragePerformanceSnapshot(
            LatencyMetricSnapshot redisRead,
            LatencyMetricSnapshot redisWrite,
            LatencyMetricSnapshot postgresRead,
            LatencyMetricSnapshot postgresWrite
    ) {
        this(redisRead, redisWrite, postgresRead, postgresWrite, Map.of(), Map.of(), Map.of(), Map.of());
    }
}
