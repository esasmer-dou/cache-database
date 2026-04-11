package com.reactor.cachedb.starter;

import java.time.Instant;

public record PerformanceHistoryPoint(
        Instant recordedAt,
        long redisReadOperationCount,
        long redisReadAverageMicros,
        long redisWriteOperationCount,
        long redisWriteAverageMicros,
        long postgresReadOperationCount,
        long postgresReadAverageMicros,
        long postgresWriteOperationCount,
        long postgresWriteAverageMicros
) {
}
