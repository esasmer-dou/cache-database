package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record ReadShapeBenchmarkReport(
        String benchmarkName,
        int warmupIterations,
        int measuredIterationsPerShape,
        int ordersPerRead,
        int fullLinesPerOrder,
        int previewLinesPerOrder,
        String disclaimer,
        String fastestAverageShape,
        double maxAverageSpreadPercent,
        List<ShapeReport> shapeReports
) {
    public record ShapeReport(
            String shapeName,
            String label,
            String positioning,
            int materializedOrdersPerOperation,
            int materializedLineViewsPerOperation,
            int estimatedObjectCountPerOperation,
            long totalLatencyNanos,
            long averageLatencyNanos,
            long p95LatencyNanos,
            long p99LatencyNanos,
            double averageLatencyVsFastestPercent
    ) {
    }
}
