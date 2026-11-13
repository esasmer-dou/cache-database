package com.reactor.cachedb.core.readmodel;

import java.time.Instant;
import java.util.Objects;

/**
 * One published generation, not an assertion of current raw-index state or a source-wide transaction.
 * Freshness starts at observedFrom; retention starts at publishedAt. A provider may conservatively use
 * publishedAt as observedThrough when publication is the upper bound of source observation.
 */
public record CountFacetSnapshot(long generation, Instant observedFrom, Instant observedThrough,
        Instant publishedAt, CountFacetValues values) {
    public CountFacetSnapshot {
        Objects.requireNonNull(observedFrom, "observedFrom");
        Objects.requireNonNull(observedThrough, "observedThrough");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(values, "values");
        if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
        if (observedFrom.isAfter(observedThrough) || observedThrough.isAfter(publishedAt))
            throw new IllegalArgumentException("Require observedFrom <= observedThrough <= publishedAt");
    }
}
