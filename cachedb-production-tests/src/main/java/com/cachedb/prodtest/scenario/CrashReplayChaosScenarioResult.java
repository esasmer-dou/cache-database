package com.reactor.cachedb.prodtest.scenario;

public record CrashReplayChaosScenarioResult(
        String scenarioName,
        boolean passed,
        boolean restartVerified,
        boolean stateVerified,
        boolean replayVerified,
        boolean rebuildVerified,
        String finalHealthStatus,
        String note
) {
}
