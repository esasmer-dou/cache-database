package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record RankedProjectionBenchmarkReport(
        String benchmarkName,
        int warmupIterations,
        int measuredIterationsPerShape,
        int datasetSize,
        int topWindowSize,
        String disclaimer,
        String fastestAverageShape,
        List<ShapeReport> shapeReports
) {
    public record ShapeReport(
            String shapeName,
            String label,
            int materializedObjectsPerOperation,
            long averageLatencyNanos,
            long p95LatencyNanos,
            long p99LatencyNanos
    ) {
    }
}
