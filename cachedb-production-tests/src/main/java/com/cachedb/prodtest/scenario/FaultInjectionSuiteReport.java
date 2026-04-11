package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record FaultInjectionSuiteReport(
        int scenarioCount,
        int successfulScenarios,
        boolean allSuccessful,
        Instant recordedAt,
        List<FaultInjectionScenarioResult> scenarios
) {
}
