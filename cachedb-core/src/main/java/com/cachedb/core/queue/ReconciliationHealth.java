package com.reactor.cachedb.core.queue;

import java.util.List;

public record ReconciliationHealth(
        AdminHealthStatus status,
        List<String> issues,
        ReconciliationMetrics metrics
) {
}
