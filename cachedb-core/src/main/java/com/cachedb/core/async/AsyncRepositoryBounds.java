package com.reactor.cachedb.core.async;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Shared finite input snapshot; entity objects themselves are not cloned. */
public final class AsyncRepositoryBounds {
    private AsyncRepositoryBounds() {}

    public static <V> List<V> copy(Collection<V> source, int maximum) {
        Objects.requireNonNull(source, "source");
        if (maximum < 0 || maximum > com.reactor.cachedb.core.query.QuerySpec.MAX_LIMIT)
            throw new IllegalArgumentException("Invalid async collection bound");
        if (source.size() > maximum) throw new IllegalArgumentException("Async collection limit exceeded");
        var copy = new ArrayList<V>(Math.min(source.size(), maximum));
        for (V value : source) {
            if (copy.size() == maximum) throw new IllegalArgumentException("Async collection limit exceeded");
            copy.add(Objects.requireNonNull(value, "collection element"));
        }
        return List.copyOf(copy);
    }
}
