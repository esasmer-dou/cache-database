package com.reactor.cachedb.starter;

import java.time.Instant;

public record AlertRouteHistoryPoint(
        Instant recordedAt,
        String routeName,
        String status,
        String escalationLevel,
        long deliveredCount,
        long failedCount,
        long droppedCount,
        String lastErrorType
) {
}
