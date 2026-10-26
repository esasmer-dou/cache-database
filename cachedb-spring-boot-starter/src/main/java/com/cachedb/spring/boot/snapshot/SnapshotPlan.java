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
        boolean contentReuse,
        List<SnapshotDirtyBinding> autoDirtyBindings) {
    public SnapshotPlan(String name, SnapshotSource<R> roots, Function<R, String> id,
            List<SnapshotSource<?>> sources, Class<V> valueType,
            Function<SnapshotRows, Function<R, Iterable<V>>> projection, SnapshotIndexSpec<R> indexSpec,
            SnapshotRootScope rootScope, boolean contentReuse) {
        this(name, roots, id, sources, valueType, projection, indexSpec, rootScope, contentReuse, List.of());
    }

    public SnapshotPlan<R, V> autoDirtyOn(String entityName, Function<Object, String> extractor) {
        return addDirtyBinding(new SnapshotDirtyBinding(entityName, extractor, false));
    }

    public SnapshotPlan<R, V> autoDirtyOnRootEntity() {
        var metadata = roots.entityMetadata();
        if (metadata == null) throw new IllegalArgumentException("Auto-dirty roots require a generated entity source");
        return addDirtyBinding(new SnapshotDirtyBinding(metadata.entityName(), String::valueOf, true));
    }

    private SnapshotPlan<R, V> addDirtyBinding(SnapshotDirtyBinding binding) {
        var bindings = new ArrayList<>(autoDirtyBindings);
        if (bindings.size() >= 64 || bindings.stream().anyMatch(value -> value.entityName().equals(binding.entityName())))
            throw new IllegalArgumentException("Auto-dirty registrations must be unique and bounded to 64");
        bindings.add(binding);
        return new SnapshotPlan<>(name, roots, id, sources, valueType, projection, indexSpec, rootScope, contentReuse, bindings);
    }
    public SnapshotPlan<R, V> withGeneratedJsonCodec() {
        return withJsonCodec(com.reactor.cachedb.core.codec.GeneratedJsonCodecs.require(valueType));
    }

    public SnapshotPlan<R, V> scopedBy(SnapshotSource<?> source, String column, Function<String, ?> parser) {
        return scoped(SnapshotRootScope.byColumn(source, column, parser));
    }

    public SnapshotPlan<R, V> scopedByString(SnapshotSource<?> source, String column) {
        return scoped(SnapshotRootScope.byStringColumn(source, column));
    }

    public <ID> TypedSnapshotRepository<ID, V> bind(SnapshotRepository repository, Function<ID, String> idSerializer) {
        if (jsonCodec() == null) throw new IllegalStateException("Binding requires an explicit or generated JSON codec");
        return TypedSnapshotRepository.decoding(repository, jsonCodec(), idSerializer);
    }

    public <ID> TypedSnapshotRepository<ID, V> bind(SnapshotRepository repository) {
        return bind(repository, String::valueOf);
    }
    /** Explicit JSON contract; preserves all existing plan constructors and record components. */
    public SnapshotPlan<R, V> withJsonCodec(
            com.reactor.cachedb.core.codec.JsonArrayCodec<V> codec) {
        Objects.requireNonNull(codec, "codec");
        if (!valueType.equals(codec.valueType()))
            throw new IllegalArgumentException(
                    "JSON codec type does not match snapshot value type");
        var delegate =
                projection instanceof JsonSnapshotProjection<R, V> encoded
                        ? encoded.delegate()
                        : projection;
        return new SnapshotPlan<>(
                name,
                roots,
                id,
                sources,
                valueType,
                new JsonSnapshotProjection<>(delegate, codec),
                indexSpec,
                rootScope,
                contentReuse, autoDirtyBindings);
    }

    @SuppressWarnings("unchecked")
    public com.reactor.cachedb.core.codec.JsonArrayCodec<V> jsonCodec() {
        return projection instanceof JsonSnapshotProjection<?, ?> encoded
                ? (com.reactor.cachedb.core.codec.JsonArrayCodec<V>) encoded.codec()
                : null;
    }

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
                contentReuse, autoDirtyBindings);
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
                        contentReuse, autoDirtyBindings);
        scope.validate(plan);
        return plan;
    }

    public <E, T> SnapshotPlan<R, V> scopedBy(SnapshotSource<E> source,
            com.reactor.cachedb.core.query.TypedColumn<E, T> column, Function<String, T> parser) {
        return scoped(SnapshotRootScope.byColumn(source, column.columnName(), parser));
    }

    public <E, T> SnapshotPlan<R, V> scopedBy(SnapshotSource<E> source,
            com.reactor.cachedb.core.query.TypedColumn<E, T> column) {
        return scopedBy(source, column, column.parser());
    }

    public SnapshotPlan<R, V> withContentReuse() {
        return new SnapshotPlan<>(
                name, roots, id, sources, valueType, projection, indexSpec, rootScope, true, autoDirtyBindings);
    }

    /** Opt-in row-local mapping: roots are decoded and spooled without retaining a root list. */
    public static <R, V> SnapshotPlan<R, V> streaming(
            String name,
            SnapshotSource<R> roots,
            Function<R, String> id,
            Class<V> valueType,
            Function<R, Iterable<V>> mapping) {
        return new SnapshotPlan<>(
                name,
                roots,
                id,
                List.of(roots),
                valueType,
                new StreamingSnapshotProjection<>(mapping));
    }

    boolean streaming() {
        var delegate =
                projection instanceof JsonSnapshotProjection<?, ?> encoded
                        ? encoded.delegate()
                        : projection;
        return sources.size() == 1 && delegate instanceof StreamingSnapshotProjection<?, ?>;
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
                streaming()
                        ? new StreamingSnapshotProjection<>(
                                root -> mapping.apply(root, projection.apply(null).apply(root)))
                        : rows -> {
                            Function<R, Iterable<V>> base = projection.apply(rows);
                            return root -> mapping.apply(root, base.apply(root));
                        },
                indexSpec,
                rootScope,
                contentReuse, autoDirtyBindings);
    }

    public SnapshotPlan {
        autoDirtyBindings = List.copyOf(autoDirtyBindings);
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
