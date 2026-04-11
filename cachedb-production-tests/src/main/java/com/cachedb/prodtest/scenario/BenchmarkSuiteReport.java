package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record BenchmarkSuiteReport(
        String suiteName,
        double scaleFactor,
        int scenarioCount,
        long totalExecutedOperations,
        long totalReadOperations,
        long totalWriteOperations,
        long totalFailedOperations,
        double averageAchievedTransactionsPerSecond,
        long worstP95LatencyMicros,
        long worstP99LatencyMicros,
        long worstWriteBehindBacklog,
        long worstRedisUsedMemoryBytes,
        long worstCompactionPendingCount,
        long totalRuntimeProfileSwitchCount,
        long maxRuntimeProfileSwitchCount,
        long worstDrainMillis,
        boolean allDrainsCompleted,
        List<String> degradedOrDownScenarios,
        List<ScenarioReport> scenarioReports
) {
}
