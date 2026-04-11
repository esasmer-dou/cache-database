package com.reactor.cachedb.starter;

import java.time.Instant;

public record AdminTelemetryResetSnapshot(
        long diagnosticsEntriesCleared,
        long incidentEntriesCleared,
        int monitoringHistorySamplesCleared,
        int alertRouteHistorySamplesCleared,
        long storagePerformanceOperationsCleared,
        Instant resetAt
) {
}
