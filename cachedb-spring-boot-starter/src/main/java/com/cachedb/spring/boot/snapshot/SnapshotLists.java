package com.reactor.cachedb.spring.boot.snapshot;

import java.util.*;
import java.util.function.Function;

/**
 * Immutable prepared lists. Missing keys return empty lists; order and duplicates are preserved.
 */
public final class SnapshotLists<K, V> {
    private final Map<K, List<V>> values;

    SnapshotLists(Map<K, List<V>> values) {
        this.values = Map.copyOf(values);
    }

    public List<V> get(K key) {
        return values.getOrDefault(key, List.of());
    }

    /** Each value is assigned once per distinct key, without deduplicating its payload fields. */
    public static <K, V> SnapshotLists<K, V> distribute(
            Iterable<V> values, Function<V, ? extends Iterable<K>> keys) {
        Map<K, List<V>> result = new HashMap<>();
        for (V value : values) {
            Objects.requireNonNull(value, "Null distributed value");
            Set<K> seen = new HashSet<>();
            for (K key : keys.apply(value)) {
                Objects.requireNonNull(key, "Null distribution key");
                if (seen.add(key))
                    result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            }
        }
        result.replaceAll((key, list) -> List.copyOf(list));
        return new SnapshotLists<>(result);
    }
}
