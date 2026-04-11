package com.reactor.cachedb.core.query;

public record QueryHistogramBucket(
        double lowerBoundInclusive,
        double upperBoundExclusive,
        long count
) {
}
