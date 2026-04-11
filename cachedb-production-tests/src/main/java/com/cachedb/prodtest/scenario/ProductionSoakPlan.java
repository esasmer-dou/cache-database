package com.reactor.cachedb.prodtest.scenario;

public record ProductionSoakPlan(
        String name,
        String scenarioName,
        double scaleFactor,
        int iterations,
        int targetDurationSeconds,
        boolean disableSafetyCaps
) {
}
