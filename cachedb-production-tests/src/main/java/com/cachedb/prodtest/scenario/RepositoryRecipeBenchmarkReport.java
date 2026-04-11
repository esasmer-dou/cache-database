package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record RepositoryRecipeBenchmarkReport(
        String benchmarkName,
        int warmupIterations,
        int measuredIterationsPerOperation,
        String disclaimer,
        String fastestAverageMode,
        String fastestP95Mode,
        double maxAverageSpreadPercent,
        List<String> comparedOperations,
        List<RepositoryRecipeModeReport> modeReports
) {
}
