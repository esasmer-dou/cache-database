package com.reactor.cachedb.starter;

import java.time.Instant;

public record PerformanceTelemetryResetSnapshot(
        long storagePerformanceOperationsCleared,
        int performanceHistorySamplesCleared,
        Instant resetAt
) {
}
