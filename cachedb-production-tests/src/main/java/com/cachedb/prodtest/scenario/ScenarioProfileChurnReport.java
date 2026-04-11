package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record ScenarioProfileChurnReport(
        String scenarioName,
        EcommerceScenarioKind kind,
        double scaleFactor,
        String degradeProfile,
        String finalRuntimeProfile,
        long runtimeProfileSwitchCount,
        String finalHealthStatus,
        List<ScenarioProfileChurnEntry> entries
) {
}
