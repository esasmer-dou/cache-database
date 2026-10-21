package com.reactor.cachedb.spring.boot.snapshot;

import java.util.function.Function;

/** Explicit capability, not a guess based on the number of SQL sources. */
final class StreamingSnapshotProjection<R, V>
        implements Function<SnapshotRows, Function<R, Iterable<V>>> {
    final Function<R, Iterable<V>> mapper;

    StreamingSnapshotProjection(Function<R, Iterable<V>> mapper) {
        this.mapper = java.util.Objects.requireNonNull(mapper);
    }

    public Function<R, Iterable<V>> apply(SnapshotRows ignored) {
        return mapper;
    }
}
