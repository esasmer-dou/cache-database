package com.reactor.cachedb.starter;

public record AlertRouteSnapshot(
        String routeName,
        boolean enabled,
        String target,
        String deliveryMode,
        String escalationPolicy,
        String escalationLevel,
        String triggerScope,
        String retryPolicy,
        String fallbackRoute,
        String status,
        long deliveredCount,
        long failedCount,
        long droppedCount,
        long lastDeliveredAtEpochMillis,
        long lastErrorAtEpochMillis,
        String lastErrorType
) {
}
