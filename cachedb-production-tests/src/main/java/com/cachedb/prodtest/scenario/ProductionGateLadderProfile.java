package com.reactor.cachedb.prodtest.scenario;

public record ProductionGateLadderProfile(
        String name,
        String scenarioName,
        double scaleFactor,
        double minimumTps,
        long maxBacklog,
        boolean requireDrain
) {
}
