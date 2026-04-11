package com.reactor.cachedb.prodtest.scenario;

import java.time.Instant;
import java.util.List;

public record ProductionSoakReport(
        String scenarioName,
        double scaleFactor,
        int iterationCount,
        long totalElapsedMillis,
        double averageTps,
        double minTps,
        double maxTps,
        long maxRedisMemoryBytes,
        long maxBacklog,
        long maxCompactionPending,
        long totalRuntimeProfileSwitches,
        boolean allRunsDrained,
        List<String> distinctHealthStatuses,
        Instant recordedAt,
        List<SoakIterationReport> iterations
) {
}
