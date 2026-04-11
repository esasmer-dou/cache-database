package com.reactor.cachedb.starter;

import java.time.Instant;
import java.util.List;

public record MonitoringTriageSnapshot(
        String overallStatus,
        String primaryBottleneck,
        String suspectedCause,
        List<String> evidence,
        Instant recordedAt
) {
}
