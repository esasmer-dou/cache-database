package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.core.model.SourceValues;

import java.util.List;

/**
 * Explicit from/to columns. Reversing an index reuses the same source, without another SQL read.
 */
public final class SnapshotRelation implements SnapshotInput {
    public record Edge(String from, String to) {}

    private final SnapshotSource<Edge> source;
    private final String fromColumn;
    private final String toColumn;
    private final boolean reversed;

    private SnapshotRelation(
            SnapshotSource<Edge> source, String from, String to, boolean reversed) {
        this.source = source;
        this.fromColumn = from;
        this.toColumn = to;
        this.reversed = reversed;
    }

    public static SnapshotRelation strings(String name, String table, String from, String to) {
        var mapping =
                new SourceMapping<>(
                        table,
                        List.of(from, to),
                        row ->
                                new Edge(
                                        SourceValues.read(row, from, String.class),
                                        SourceValues.read(row, to, String.class)));
        return new SnapshotRelation(SnapshotSource.entity(name, mapping), from, to, false);
    }

    public SnapshotRelation where(SnapshotPredicate predicate) {
        return new SnapshotRelation(source.where(predicate), fromColumn, toColumn, reversed);
    }

    public SnapshotRelation reverse() {
        return new SnapshotRelation(source, fromColumn, toColumn, !reversed);
    }

    public SnapshotSource<Edge> source() {
        return source;
    }

    public SnapshotSelection fromIds() {
        return source.select(reversed ? toColumn : fromColumn);
    }

    public SnapshotSelection toIds() {
        return source.select(reversed ? fromColumn : toColumn);
    }

    String from(Edge edge) {
        return reversed ? edge.to() : edge.from();
    }

    String to(Edge edge) {
        return reversed ? edge.from() : edge.to();
    }
}
