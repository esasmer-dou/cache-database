package com.reactor.cachedb.starter;

import java.time.Instant;
import java.util.List;

public record ApiProductSnapshot(
        int registeredEntityCount,
        int maxRegisteredEntities,
        int maxColumnsPerOperation,
        Instant recordedAt,
        List<ApiEntitySnapshot> entities
) {
}
