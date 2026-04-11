package com.reactor.cachedb.starter;

import java.time.Instant;

public record IncidentSeverityTrendPoint(
        Instant recordedAt,
        long infoCount,
        long warningCount,
        long criticalCount,
        long totalCount
) {
}
