package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record ProductionSoakPlanReport(
        String planName,
        Instant recordedAt,
        int runCount,
        List<ProductionSoakPlanResult> results
) {
}
