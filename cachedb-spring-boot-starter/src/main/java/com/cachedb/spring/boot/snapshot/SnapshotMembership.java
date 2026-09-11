package com.reactor.cachedb.spring.boot.snapshot;

import java.util.*;

/** Prepared membership sets, built directly without intermediate lists. Not a Redis index. */
public final class SnapshotMembership<K, V> {
    private final Map<K, Set<V>> values;

    SnapshotMembership(Map<K, Set<V>> values) {
        this.values = Map.copyOf(values);
    }

    public boolean containsAny(K key, Iterable<V> candidates) {
        Set<V> members = values.getOrDefault(key, Set.of());
        for (V value : candidates) if (members.contains(value)) return true;
        return false;
    }

    public boolean containsAll(K key, Iterable<V> candidates) {
        Set<V> members = values.getOrDefault(key, Set.of());
        for (V value : candidates) if (!members.contains(value)) return false;
        return true;
    }
}
