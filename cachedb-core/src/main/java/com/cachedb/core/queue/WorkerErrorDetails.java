package com.reactor.cachedb.core.queue;

public record WorkerErrorDetails(
        String errorType,
        String errorMessage,
        String rootErrorType,
        String rootErrorMessage,
        String origin,
        String stackTrace
) {
}
