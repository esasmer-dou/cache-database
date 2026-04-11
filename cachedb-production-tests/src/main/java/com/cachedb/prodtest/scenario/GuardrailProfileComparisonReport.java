package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record GuardrailProfileComparisonReport(
        String suiteName,
        double scaleFactor,
        List<String> comparedProfiles,
        int scenarioCount,
        int totalRuns,
        double highestAverageBalanceScore,
        String bestOverallProfile,
        long worstWriteBehindBacklog,
        long worstRedisUsedMemoryBytes,
        long worstCompactionPendingCount,
        List<GuardrailProfileSummary> profileSummaries,
        List<GuardrailScenarioComparisonReport> scenarioComparisons
) {
}
