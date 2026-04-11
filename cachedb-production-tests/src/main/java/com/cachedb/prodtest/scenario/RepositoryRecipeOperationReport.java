package com.reactor.cachedb.prodtest.scenario;

public record RepositoryRecipeOperationReport(
        String operationName,
        String category,
        long measuredOperations,
        long totalLatencyNanos,
        long averageLatencyNanos,
        long p95LatencyNanos,
        long p99LatencyNanos
) {
}
