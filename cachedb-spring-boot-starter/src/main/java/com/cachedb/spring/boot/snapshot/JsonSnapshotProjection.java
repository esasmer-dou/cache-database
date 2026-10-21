package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.codec.JsonArrayCodec;

import java.util.function.Function;

/** Adds codec selection without changing SnapshotPlan's public record shape. */
record JsonSnapshotProjection<R, V>(
        Function<SnapshotRows, Function<R, Iterable<V>>> delegate, JsonArrayCodec<V> codec)
        implements Function<SnapshotRows, Function<R, Iterable<V>>> {
    public Function<R, Iterable<V>> apply(SnapshotRows rows) {
        return delegate.apply(rows);
    }
}
