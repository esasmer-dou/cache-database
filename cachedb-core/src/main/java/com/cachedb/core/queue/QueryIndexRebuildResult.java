package com.reactor.cachedb.core.queue;

import java.time.Instant;

public record QueryIndexRebuildResult(
        String entityName,
        String namespace,
        long rebuiltEntityCount,
        long clearedKeyCount,
        boolean degradedCleared,
        boolean automatic,
        Instant recordedAt,
        String note
) {
}
