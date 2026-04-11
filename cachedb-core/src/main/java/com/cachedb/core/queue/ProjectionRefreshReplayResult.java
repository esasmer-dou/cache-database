package com.reactor.cachedb.core.queue;

public record ProjectionRefreshReplayResult(
        String entryId,
        boolean replayed,
        String enqueuedEntryId,
        String message
) {
}
