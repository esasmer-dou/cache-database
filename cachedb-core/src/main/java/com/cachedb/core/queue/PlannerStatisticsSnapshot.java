package com.reactor.cachedb.core.queue;

public record PlannerStatisticsSnapshot(
        long estimateKeyCount,
        long histogramKeyCount,
        long learnedStatisticsKeyCount
) {
}
