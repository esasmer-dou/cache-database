package com.reactor.cachedb.core.query;

import java.util.List;

public record QueryCardinalityEstimate(
        String expression,
        long estimatedCardinality,
        long estimatedCost,
        long sampledCardinality,
        double selectivityRatio,
        List<QueryHistogramBucket> histogram
) {
}
