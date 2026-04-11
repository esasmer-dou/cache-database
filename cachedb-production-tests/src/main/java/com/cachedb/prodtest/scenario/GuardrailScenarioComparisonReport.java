package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record GuardrailScenarioComparisonReport(
        String scenarioName,
        EcommerceScenarioKind kind,
        String bestBalanceProfile,
        String bestThroughputProfile,
        String lowestBacklogProfile,
        String lowestMemoryProfile,
        List<GuardrailProfileRunReport> profileRuns
) {
}
