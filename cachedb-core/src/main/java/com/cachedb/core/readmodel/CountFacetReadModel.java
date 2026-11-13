package com.reactor.cachedb.core.readmodel;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Explicit precomputed COUNT/FACET snapshots, separate from live repository aggregates.
 * Raw index, owner and tombstone mutations do not automatically invalidate a published generation.
 * Callers own source selection, tenant scope, authorization and the supplier's observation semantics.
 * Implementations must be thread-safe and must not silently fall back to a live query.
 */
public interface CountFacetReadModel {
    /** Stable identity and lifetime policy for this model. */
    CountFacetDefinition definition();

    /**
     * Inspects retained state without invoking a supplier or refreshing it. Freshness is measured
     * from observation start, not publication; age equal to maxAge is STALE. Retention expires
     * after publication and is independent of freshness. Inspection failures propagate, not MISSING.
     */
    CountFacetRead inspect();

    /**
     * Attempts one leased refresh. A null supplier is rejected. BUSY must not invoke the supplier.
     * After acquiring the lease, invoke it at most once; null values fail without publication.
     * Publish a new generation only while the same lease is valid. Lost authority returns LEASE_LOST
     * without publishing its computed values. Supplier exceptions propagate unchanged after owned
     * lease release, without automatic retry or replacement of the prior snapshot.
     * Backends must bound their lease operations and avoid releasing another owner's lease.
     */
    CountFacetRefresh refresh(Supplier<CountFacetValues> supplier);

    /** Returns the inspected fresh snapshot, or fails without refreshing or querying raw data. */
    default CountFacetSnapshot requireFresh() {
        CountFacetRead read = Objects.requireNonNull(inspect(), "inspect result");
        if (read.status() != CountFacetRead.Status.FRESH)
            throw new IllegalStateException("Count/facet read model is " + read.status());
        CountFacetSnapshot snapshot = read.snapshot().orElseThrow();
        CountFacetDefinition policy = Objects.requireNonNull(definition(), "definition");
        if (Duration.between(snapshot.observedFrom(), read.checkedAt()).compareTo(policy.maxAge()) >= 0)
            throw new IllegalStateException("Count/facet read model is STALE");
        return snapshot;
    }
}
