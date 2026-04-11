package com.reactor.cachedb.core.queue;

public record AdminReportJobSnapshot(
        long runCount,
        long writtenFileCount,
        long lastRunAtEpochMillis,
        String lastOutputDirectory,
        long lastErrorAtEpochMillis,
        String lastErrorType,
        String lastErrorMessage,
        String lastErrorRootType,
        String lastErrorRootMessage,
        String lastErrorOrigin,
        String lastErrorStackTrace
) {
}
