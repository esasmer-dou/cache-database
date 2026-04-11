package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record RepositoryRecipeModeReport(
        RepositoryRecipeMode mode,
        String label,
        String positioning,
        long measuredOperations,
        long totalLatencyNanos,
        long averageLatencyNanos,
        long p95LatencyNanos,
        long p99LatencyNanos,
        double averageLatencyVsMinimalPercent,
        List<RepositoryRecipeOperationReport> operationReports
) {
}
