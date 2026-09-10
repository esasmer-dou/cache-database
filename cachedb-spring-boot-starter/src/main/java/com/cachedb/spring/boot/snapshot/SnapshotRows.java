package com.reactor.cachedb.spring.boot.snapshot;

import java.util.*;
import java.util.function.Function;

/** One consistent SQL snapshot. Typed source handles avoid reflective entity traversal. */
public final class SnapshotRows {
    private final Map<SnapshotSource<?>, List<?>> rows;

    SnapshotRows(Map<SnapshotSource<?>, List<?>> rows) {
        this.rows = Map.copyOf(rows);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> get(SnapshotSource<T> source) {
        List<?> result = rows.get(source);
        if (result == null)
            throw new IllegalArgumentException("Undeclared snapshot source: " + source.name());
        return (List<T>) result;
    }

    public <T, K> Map<K, T> unique(SnapshotSource<T> source, Function<T, K> key) {
        Map<K, T> result = new HashMap<>();
        for (T row : get(source)) {
            K id = Objects.requireNonNull(key.apply(row), "Null snapshot identity");
            if (result.putIfAbsent(id, row) != null)
                throw new IllegalStateException(
                        "Duplicate identity in snapshot source " + source.name());
        }
        return Collections.unmodifiableMap(result);
    }

    public <T, K, V> Map<K, List<V>> group(
            SnapshotSource<T> source,
            Function<T, K> key,
            Function<T, V> value,
            Comparator<V> order) {
        Map<K, List<V>> result = new HashMap<>();
        for (T row : get(source)) {
            K id = Objects.requireNonNull(key.apply(row), "Null snapshot relation key");
            result.computeIfAbsent(id, ignored -> new ArrayList<>())
                    .add(Objects.requireNonNull(value.apply(row), "Null snapshot relation target"));
        }
        result.replaceAll(
                (keyValue, values) -> {
                    if (order != null) values.sort(order);
                    return List.copyOf(values);
                });
        return Collections.unmodifiableMap(result);
    }
}
