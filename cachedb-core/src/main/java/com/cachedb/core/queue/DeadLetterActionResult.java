package com.reactor.cachedb.core.queue;

public record DeadLetterActionResult(
        String entryId,
        String action,
        boolean applied,
        String status
) {
}
