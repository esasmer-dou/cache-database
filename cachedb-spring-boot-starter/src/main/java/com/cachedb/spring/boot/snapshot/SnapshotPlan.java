package com.reactor.cachedb.spring.boot.snapshot;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/** ID-addressed, complete-catalog projection. Preparation contains business mapping, never I/O. */
public record SnapshotPlan<R, V>(
        String name,
        SnapshotSource<R> roots,
        Function<R, String> id,
        List<SnapshotSource<?>> sources,
        Class<V> valueType,
        Function<SnapshotRows, Function<R, Iterable<V>>> projection,
        SnapshotIndexSpec<R> indexSpec,
        SnapshotRootScope rootScope,
        boolean contentReuse) {
    public SnapshotPlan(
            String name,
            SnapshotSource<R> roots,
            Function<R, String> id,
            List<SnapshotSource<?>> sources,
            Class<V> valueType,
            Function<SnapshotRows, Function<R, Iterable<V>>> projection) {
        this(name, roots, id, sources, valueType, projection, null, null, false);
    }

    public SnapshotPlan<R, V> indexed(SnapshotIndexSpec<R> spec) {
        return new SnapshotPlan<>(
                name,
                roots,
                id,
                sources,
                valueType,
                projection,
                Objects.requireNonNull(spec),
                rootScope,
                contentReuse);
    }

    public SnapshotPlan<R, V> scoped(SnapshotRootScope scope) {
        var plan =
                new SnapshotPlan<>(
                        name,
                        roots,
                        id,
                        sources,
                        valueType,
                        projection,
                        indexSpec,
                        Objects.requireNonNull(scope),
                        contentReuse);
        scope.validate(plan);
        return plan;
    }

    public SnapshotPlan<R, V> withContentReuse() {
        return new SnapshotPlan<>(
                name, roots, id, sources, valueType, projection, indexSpec, rootScope, true);
    }

    /** Keeps the SQL read set explicit; declarations are never discovered by reflection. */
    public static List<SnapshotSource<?>> inputs(SnapshotInput... inputs) {
        List<SnapshotSource<?>> result = new ArrayList<>();
        for (SnapshotInput input : inputs) result.add(Objects.requireNonNull(input).source());
        return List.copyOf(result);
    }

    /** Reuses business rules in a separate job; it does not promise cross-plan atomicity. */
    public <U> SnapshotPlan<R, U> map(
            String name, Class<U> type, BiFunction<R, Iterable<V>, Iterable<U>> mapping) {
        Objects.requireNonNull(mapping);
        return new SnapshotPlan<>(
                name,
                roots,
                id,
                sources,
                type,
                rows -> {
                    Function<R, Iterable<V>> base = projection.apply(rows);
                    return root -> mapping.apply(root, base.apply(root));
                },
                indexSpec,
                rootScope,
                contentReuse);
    }

    public SnapshotPlan {
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid snapshot plan name");
        Objects.requireNonNull(roots);
        Objects.requireNonNull(id);
        Objects.requireNonNull(valueType);
        Objects.requireNonNull(projection);
        sources = List.copyOf(sources);
        if (!sources.contains(roots))
            throw new IllegalArgumentException("Snapshot roots must be declared");
        Set<String> names = new HashSet<>();
        for (var source : sources)
            if (!names.add(source.name()))
                throw new IllegalArgumentException("Duplicate snapshot source name");
    }
}
