package com.reactor.cachedb.core.query;

public record QueryExplainStep(
        String stage,
        String expression,
        String strategy,
        boolean indexed,
        int candidateCount,
        long estimatedCost,
        String detail
) {
}
