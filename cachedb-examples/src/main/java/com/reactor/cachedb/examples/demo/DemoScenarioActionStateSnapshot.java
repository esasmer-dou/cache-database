package com.reactor.cachedb.examples.demo;

public record DemoScenarioActionStateSnapshot(
        String state,
        String label,
        String error,
        long lastQueuedAtEpochMillis,
        long lastCompletedAtEpochMillis,
        long lastActionId
) {
}
