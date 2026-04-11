package com.reactor.cachedb.starter;

public record BackgroundWorkerErrorSnapshot(
        String workerName,
        String serviceName,
        String status,
        boolean recent,
        long lastErrorAtEpochMillis,
        String errorType,
        String rootErrorType,
        String errorMessage,
        String rootErrorMessage,
        String origin,
        String stackTrace
) {
}
