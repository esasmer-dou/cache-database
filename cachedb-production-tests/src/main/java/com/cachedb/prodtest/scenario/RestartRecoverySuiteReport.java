package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record RestartRecoverySuiteReport(
        int cycleCount,
        int successfulCycles,
        boolean allSuccessful,
        Instant recordedAt,
        List<RestartRecoveryCycleReport> cycles
) {
}
