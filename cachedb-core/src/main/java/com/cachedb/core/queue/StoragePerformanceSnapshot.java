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
        Map<String, LatencyMetricSnapshot> postgresWriteBreakdown,
        Map<String, CacheAdmissionMetricSnapshot> cacheAdmissionBreakdown
) {
    public StoragePerformanceSnapshot(
            LatencyMetricSnapshot redisRead,
            LatencyMetricSnapshot redisWrite,
            LatencyMetricSnapshot postgresRead,
            LatencyMetricSnapshot postgresWrite,
            Map<String, LatencyMetricSnapshot> redisReadBreakdown,
            Map<String, LatencyMetricSnapshot> redisWriteBreakdown,
            Map<String, LatencyMetricSnapshot> postgresReadBreakdown,
            Map<String, LatencyMetricSnapshot> postgresWriteBreakdown
    ) {
        this(
                redisRead,
                redisWrite,
                postgresRead,
                postgresWrite,
                redisReadBreakdown,
                redisWriteBreakdown,
                postgresReadBreakdown,
                postgresWriteBreakdown,
                Map.of()
        );
    }

    public StoragePerformanceSnapshot(
            LatencyMetricSnapshot redisRead,
            LatencyMetricSnapshot redisWrite,
            LatencyMetricSnapshot postgresRead,
            LatencyMetricSnapshot postgresWrite
    ) {
        this(redisRead, redisWrite, postgresRead, postgresWrite, Map.of(), Map.of(), Map.of(), Map.of());
    }
}
