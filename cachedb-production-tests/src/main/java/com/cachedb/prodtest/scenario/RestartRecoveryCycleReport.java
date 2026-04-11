package com.reactor.cachedb.prodtest.scenario;

public record RestartRecoveryCycleReport(
        int cycle,
        boolean persisted,
        boolean recovered,
        boolean rebuildSucceeded,
        String healthStatus
) {
}
