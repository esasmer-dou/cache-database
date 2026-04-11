package com.reactor.cachedb.core.queue;

import java.util.List;

public record BulkDeadLetterActionResult(
        String action,
        int requestedCount,
        int appliedCount,
        int failedCount,
        List<DeadLetterActionResult> results
) {
}
