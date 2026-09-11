package com.reactor.cachedb.spring.boot.snapshot;

import java.util.Objects;

/** One source column used as an SQL subquery. It does not load IDs into application memory. */
public record SnapshotSelection(SnapshotSource<?> source, String column) {
    public SnapshotSelection {
        Objects.requireNonNull(source, "source").checkColumn(column);
    }
}
