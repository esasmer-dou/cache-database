package com.reactor.cachedb.core.queue;

import java.time.Instant;
import java.util.Map;

public record RuntimeProfileChurnRecord(
        String entryId,
        String source,
        String note,
        String fromProfile,
        String toProfile,
        String pressureLevel,
        Instant recordedAt,
        Map<String, String> fields
) {
}
