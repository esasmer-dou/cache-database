package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public record ScenarioReport(
        String scenarioName,
        EcommerceScenarioKind kind,
        double scaleFactor,
        long executedOperations,
        long readOperations,
        long writeOperations,
        long failedOperations,
        long elapsedMillis,
        double achievedTransactionsPerSecond,
        long averageLatencyMicros,
        long p95LatencyMicros,
        long p99LatencyMicros,
        long maxLatencyMicros,
        long drainMillis,
        boolean drainCompleted,
        long writeBehindStreamLength,
        long deadLetterStreamLength,
        long plannerLearnedStatsCount,
        long incidentDeliveryDeadLetterCount,
        long incidentDeliveryClaimedCount,
        long redisUsedMemoryBytes,
        long redisUsedMemoryPeakBytes,
        long redisMaxMemoryBytes,
        long compactionPendingCount,
        long compactionPayloadCount,
        long hardRejectedWriteCount,
        long producerHighPressureDelayCount,
        long producerCriticalPressureDelayCount,
        String redisPressureLevel,
        String degradeProfile,
        String finalRuntimeProfile,
        long runtimeProfileSwitchCount,
        List<String> runtimeProfileTimeline,
        String finalHealthStatus,
        String notes
) {
}
