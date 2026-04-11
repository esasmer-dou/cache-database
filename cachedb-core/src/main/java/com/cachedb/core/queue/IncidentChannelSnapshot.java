package com.reactor.cachedb.core.queue;

public record IncidentChannelSnapshot(
        String channelName,
        long deliveredCount,
        long failedCount,
        long droppedCount,
        long lastDeliveredAtEpochMillis,
        long lastErrorAtEpochMillis,
        String lastErrorType,
        String lastErrorMessage
) {
}
