package com.reactor.cachedb.core.readmodel;

import java.util.Objects;
import java.util.Optional;

/** Only PUBLISHED certifies a snapshot; BUSY and LEASE_LOST never carry a publication receipt. */
public record CountFacetRefresh(Status status, Optional<CountFacetSnapshot> snapshot) {
    public enum Status { PUBLISHED, BUSY, LEASE_LOST }

    public CountFacetRefresh {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(snapshot, "snapshot");
        if ((status == Status.PUBLISHED) != snapshot.isPresent())
            throw new IllegalArgumentException("Only PUBLISHED must have a snapshot");
    }
}
