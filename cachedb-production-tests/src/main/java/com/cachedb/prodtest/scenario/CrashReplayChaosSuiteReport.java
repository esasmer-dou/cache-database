package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record CrashReplayChaosSuiteReport(
        int scenarioCount,
        int successfulScenarios,
        boolean allSuccessful,
        Instant recordedAt,
        List<CrashReplayChaosScenarioResult> scenarios
) {
}
