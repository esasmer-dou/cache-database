package com.reactor.cachedb.prodtest.scenario;

public record FaultInjectionScenarioResult(
        String scenarioName,
        boolean passed,
        boolean faultInjected,
        boolean recoveryVerified,
        boolean replayOrderingVerified,
        boolean rebuildVerified,
        String finalHealthStatus,
        String note
) {
}
