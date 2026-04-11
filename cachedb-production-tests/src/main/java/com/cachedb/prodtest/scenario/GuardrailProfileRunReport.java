package com.reactor.cachedb.prodtest.scenario;

public record GuardrailProfileRunReport(
        String profileName,
        ScenarioReport scenarioReport,
        double throughputRatio,
        double backlogRatio,
        double memoryRatio,
        double p99Ratio,
        double pressureFactor,
        double balanceScore
) {
}
