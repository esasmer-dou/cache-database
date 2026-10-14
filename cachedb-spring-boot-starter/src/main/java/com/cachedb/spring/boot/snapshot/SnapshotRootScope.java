package com.reactor.cachedb.spring.boot.snapshot;

import java.util.*;
import java.util.function.Function;

/** Explicit SQL narrowing for every input of a partial root refresh. */
public final class SnapshotRootScope {
    private final Map<SnapshotSource<?>, Function<List<String>, SnapshotPredicate>> filters;
    private final Set<SnapshotSource<?>> shared;

    private SnapshotRootScope(
            Map<SnapshotSource<?>, Function<List<String>, SnapshotPredicate>> filters,
            Set<SnapshotSource<?>> shared) {
        this.filters = Map.copyOf(filters);
        this.shared = Set.copyOf(shared);
    }

    public static Builder builder() {
        return new Builder();
    }

    SnapshotSource<?> narrow(SnapshotSource<?> source, List<String> ids) {
        if (shared.contains(source)) return source;
        var filter = filters.get(source);
        if (filter == null)
            throw new IllegalStateException("Missing root scope for source: " + source.name());
        return source.where(Objects.requireNonNull(filter.apply(ids), "root scope predicate"));
    }

    void validate(SnapshotPlan<?, ?> plan) {
        if (shared.contains(plan.roots()) || !filters.containsKey(plan.roots()))
            throw new IllegalArgumentException(
                    "Root source must have an explicit scoped predicate");
        for (var source : plan.sources())
            if (!shared.contains(source) && !filters.containsKey(source))
                throw new IllegalArgumentException(
                        "Missing root scope for source: " + source.name());
    }

    public static final class Builder {
        private final Map<SnapshotSource<?>, Function<List<String>, SnapshotPredicate>> filters =
                new LinkedHashMap<>();
        private final Set<SnapshotSource<?>> shared = new HashSet<>();

        public Builder source(
                SnapshotInput source, Function<List<String>, SnapshotPredicate> filter) {
            var key = Objects.requireNonNull(source).source();
            if (shared.contains(key)
                    || filters.putIfAbsent(key, Objects.requireNonNull(filter)) != null)
                throw new IllegalArgumentException("Duplicate scoped source: " + key.name());
            return this;
        }

        public Builder shared(SnapshotInput source) {
            var key = Objects.requireNonNull(source).source();
            if (filters.containsKey(key) || !shared.add(key))
                throw new IllegalArgumentException("Duplicate shared source: " + key.name());
            return this;
        }

        public SnapshotRootScope build() {
            return new SnapshotRootScope(filters, shared);
        }
    }
}
