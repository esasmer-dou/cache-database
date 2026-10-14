package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.core.model.SourceValues;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Typed relation views retain one source identity, including reversed views. */
public final class TypedSnapshotRelation<F, T> implements SnapshotInput {
    public record Edge<F, T>(F from, T to) {}

    private final SnapshotSource<?> source;
    private final String fromColumn;
    private final String toColumn;
    private final Function<Object, F> from;
    private final Function<Object, T> to;

    private TypedSnapshotRelation(
            SnapshotSource<?> source,
            String fromColumn,
            String toColumn,
            Function<Object, F> from,
            Function<Object, T> to) {
        this.source = source;
        this.fromColumn = fromColumn;
        this.toColumn = toColumn;
        this.from = from;
        this.to = to;
    }

    public static <F, T> TypedSnapshotRelation<F, T> of(
            String name, String table, String from, Class<F> fromType, String to, Class<T> toType) {
        Objects.requireNonNull(fromType, "fromType");
        Objects.requireNonNull(toType, "toType");
        var columns = List.copyOf(new java.util.LinkedHashSet<>(List.of(from, to)));
        var mapping =
                new SourceMapping<Edge<F, T>>(
                        table,
                        columns,
                        row ->
                                new Edge<>(
                                        SourceValues.read(row, from, fromType),
                                        SourceValues.read(row, to, toType)));
        return new TypedSnapshotRelation<>(
                SnapshotSource.entity(name, mapping),
                from,
                to,
                row -> fromType.cast(((Edge<?, ?>) row).from()),
                row -> toType.cast(((Edge<?, ?>) row).to()));
    }

    @Override
    public SnapshotSource<?> source() {
        return source;
    }

    public TypedSnapshotRelation<F, T> where(SnapshotPredicate predicate) {
        return new TypedSnapshotRelation<>(source.where(predicate), fromColumn, toColumn, from, to);
    }

    public TypedSnapshotRelation<T, F> reverse() {
        return new TypedSnapshotRelation<>(source, toColumn, fromColumn, to, from);
    }

    public SnapshotSelection fromIds() {
        return source.select(fromColumn);
    }

    public SnapshotSelection toIds() {
        return source.select(toColumn);
    }

    F from(Object row) {
        return from.apply(row);
    }

    T to(Object row) {
        return to.apply(row);
    }
}
