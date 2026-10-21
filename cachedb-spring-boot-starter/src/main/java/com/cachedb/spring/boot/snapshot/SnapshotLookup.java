package com.reactor.cachedb.spring.boot.snapshot;

import java.util.List;
import java.util.Objects;

/** Read state never causes an implicit source read or refresh. */
public record SnapshotLookup<V>(
        String id,
        SnapshotStatus status,
        List<V> values,
        String rawPayload,
        long refreshedAt,
        long ageMillis,
        String generation) {
    public SnapshotLookup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        values = values == null ? List.of() : List.copyOf(values);
        if (ageMillis < 0) throw new IllegalArgumentException("ageMillis must not be negative");
    }

    public boolean isPresent() {
        return status == SnapshotStatus.FOUND || status == SnapshotStatus.STALE_WARNING;
    }

    public boolean isStaleWarning() {
        return status == SnapshotStatus.STALE_WARNING;
    }

    public boolean isSnapshotAvailable() {
        return status != SnapshotStatus.SNAPSHOT_UNAVAILABLE;
    }
}
