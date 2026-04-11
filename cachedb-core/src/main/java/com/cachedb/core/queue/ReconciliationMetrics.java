package com.reactor.cachedb.core.queue;

public record ReconciliationMetrics(
        long writeBehindStreamLength,
        long deadLetterStreamLength,
        long reconciliationStreamLength,
        long archiveStreamLength,
        long diagnosticsStreamLength,
        ProjectionRefreshSnapshot projectionRefreshSnapshot,
        WriteBehindWorkerSnapshot writeBehindWorkerSnapshot,
        DeadLetterRecoverySnapshot deadLetterRecoverySnapshot,
        RecoveryCleanupSnapshot recoveryCleanupSnapshot,
        AdminReportJobSnapshot adminReportJobSnapshot,
        IncidentDeliverySnapshot incidentDeliverySnapshot,
        PlannerStatisticsSnapshot plannerStatisticsSnapshot,
        RedisGuardrailSnapshot redisGuardrailSnapshot,
        RedisRuntimeProfileSnapshot redisRuntimeProfileSnapshot
) {
}
