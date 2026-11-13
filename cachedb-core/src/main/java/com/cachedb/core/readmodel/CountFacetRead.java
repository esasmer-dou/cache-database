package com.reactor.cachedb.core.readmodel;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit inspection result. MISSING has no snapshot; FRESH and STALE retain one.
 * FRESH requires checkedAt - observedFrom strictly less than the definition's maxAge.
 * Status is determined by the provider using that definition, never by publication age.
 */
public record CountFacetRead(Status status, Instant checkedAt, Optional<CountFacetSnapshot> snapshot) {
    public enum Status { MISSING, FRESH, STALE }

    public CountFacetRead {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(snapshot, "snapshot");
        if ((status == Status.MISSING) != snapshot.isEmpty())
            throw new IllegalArgumentException("Only MISSING may have no snapshot");
        if (snapshot.isPresent() && checkedAt.isBefore(snapshot.orElseThrow().publishedAt()))
            throw new IllegalArgumentException("checkedAt must not precede publication");
    }
}
