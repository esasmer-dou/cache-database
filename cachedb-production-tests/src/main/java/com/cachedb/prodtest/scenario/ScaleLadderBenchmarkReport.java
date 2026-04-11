package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record ScaleLadderBenchmarkReport(
        String suiteName,
        List<Double> scaleFactors,
        int runCount,
        long totalExecutedOperations,
        long totalFailedOperations,
        double bestAverageAchievedTransactionsPerSecond,
        long worstP99LatencyMicros,
        long worstWriteBehindBacklog,
        long worstRedisUsedMemoryBytes,
        long worstCompactionPendingCount,
        long totalRuntimeProfileSwitchCount,
        long maxRuntimeProfileSwitchCount,
        boolean allRunsDrained,
        List<BenchmarkSuiteReport> suiteReports
) {
}
