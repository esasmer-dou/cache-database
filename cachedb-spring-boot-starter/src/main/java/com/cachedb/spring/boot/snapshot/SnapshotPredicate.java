package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.query.CacheField;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, parameterized source predicates. Subqueries execute in SQL, not as Java ID lists. */
public final class SnapshotPredicate {
    private interface Expression {
        String sql(String product, List<Object> parameters);
    }

    private final Expression expression;
    private final Set<String> columns;

    private SnapshotPredicate(Expression expression, Set<String> columns) {
        this.expression = expression;
        this.columns = Set.copyOf(columns);
    }

    public static <E, V> SnapshotPredicate eq(CacheField<E, V> column, V value) {
        return eq(column.columnName(), value);
    }

    public static SnapshotPredicate eq(String column, Object value) {
        SnapshotSource.quote(column);
        if (value != null
                && !(value instanceof String
                        || value instanceof Byte
                        || value instanceof Short
                        || value instanceof Integer
                        || value instanceof Long
                        || value instanceof Float
                        || value instanceof Double
                        || value instanceof java.math.BigDecimal
                        || value instanceof java.math.BigInteger
                        || value instanceof Boolean
                        || value instanceof java.util.UUID
                        || value instanceof java.time.LocalDate
                        || value instanceof java.time.LocalDateTime
                        || value instanceof java.time.LocalTime
                        || value instanceof java.time.Instant
                        || value instanceof java.time.OffsetDateTime
                        || value instanceof java.time.OffsetTime))
            throw new IllegalArgumentException("Predicate values must be immutable scalars");
        return new SnapshotPredicate(
                (product, parameters) -> {
                    String name = SnapshotSource.column(column, product);
                    if (value == null) return name + " IS NULL";
                    parameters.add(value);
                    return name + " = ?";
                },
                Set.of(column));
    }

    public static SnapshotPredicate in(String column, SnapshotSelection selection) {
        SnapshotSource.quote(column);
        Objects.requireNonNull(selection, "selection");
        return new SnapshotPredicate(
                (product, parameters) ->
                        SnapshotSource.column(column, product)
                                + " IN ("
                                + selection
                                        .source()
                                        .selectSql(selection.column(), product, parameters)
                                + ")",
                Set.of(column));
    }

    public static <E, V> SnapshotPredicate in(
            CacheField<E, V> column, SnapshotSelection selection) {
        return in(column.columnName(), selection);
    }

    public SnapshotPredicate and(SnapshotPredicate other) {
        return combine(other, " AND ");
    }

    public SnapshotPredicate or(SnapshotPredicate other) {
        return combine(other, " OR ");
    }

    private SnapshotPredicate combine(SnapshotPredicate other, String operator) {
        Objects.requireNonNull(other, "other");
        var names = new java.util.HashSet<>(columns);
        names.addAll(other.columns);
        return new SnapshotPredicate(
                (product, parameters) ->
                        "("
                                + sql(product, parameters)
                                + operator
                                + other.sql(product, parameters)
                                + ")",
                names);
    }

    String sql(String product, List<Object> parameters) {
        return expression.sql(product, parameters);
    }

    Set<String> columns() {
        return columns;
    }
}
