package com.reactor.cachedb.prodtest.scenario;

public record ProductionGateCheck(
        String name,
        ProductionGateStatus status,
        String summary,
        String details
) {
}
