package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.query.CacheField;

import java.util.Collection;
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
        validateScalar(value);
        return comparison(column, value, " = ?", " IS NULL");
    }

    private static void validateScalar(Object value) {
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
                        || value instanceof java.time.OffsetTime
                        || value instanceof Enum<?>))
            throw new IllegalArgumentException("Predicate values must be immutable scalars");
        if (value instanceof Float f && !Float.isFinite(f)
                || value instanceof Double d && !Double.isFinite(d))
            throw new IllegalArgumentException("Predicate numbers must be finite");
    }

    private static SnapshotPredicate comparison(
            String column, Object value, String operator, String nullOperator) {
        SnapshotSource.quote(column);
        validateScalar(value);
        if (nullOperator == null) Objects.requireNonNull(value, "Comparison value");
        return new SnapshotPredicate(
                (product, parameters) -> {
                    String name = SnapshotSource.column(column, product);
                    if (value == null) return name + nullOperator;
                    parameters.add(value instanceof Enum<?> e ? e.name() : value);
                    return name + operator;
                },
                Set.of(column));
    }

    public static SnapshotPredicate ne(String column, Object value) {
        return comparison(column, value, " <> ?", " IS NOT NULL");
    }

    public static <E, V> SnapshotPredicate ne(CacheField<E, V> column, V value) {
        return ne(column.columnName(), value);
    }

    public static SnapshotPredicate gt(String column, Object value) {
        return comparison(column, value, " > ?", null);
    }

    public static <E, V> SnapshotPredicate gt(CacheField<E, V> column, V value) {
        return gt(column.columnName(), value);
    }

    public static SnapshotPredicate gte(String column, Object value) {
        return comparison(column, value, " >= ?", null);
    }

    public static <E, V> SnapshotPredicate gte(CacheField<E, V> column, V value) {
        return gte(column.columnName(), value);
    }

    public static SnapshotPredicate lt(String column, Object value) {
        return comparison(column, value, " < ?", null);
    }

    public static <E, V> SnapshotPredicate lt(CacheField<E, V> column, V value) {
        return lt(column.columnName(), value);
    }

    public static SnapshotPredicate lte(String column, Object value) {
        return comparison(column, value, " <= ?", null);
    }

    public static <E, V> SnapshotPredicate lte(CacheField<E, V> column, V value) {
        return lte(column.columnName(), value);
    }

    public static SnapshotPredicate isNull(String column) {
        return eq(column, null);
    }

    public static <E, V> SnapshotPredicate isNull(CacheField<E, V> column) {
        return isNull(column.columnName());
    }

    public static SnapshotPredicate isNotNull(String column) {
        return ne(column, null);
    }

    public static <E, V> SnapshotPredicate isNotNull(CacheField<E, V> column) {
        return isNotNull(column.columnName());
    }

    public static SnapshotPredicate between(String column, Object lower, Object upper) {
        SnapshotSource.quote(column);
        validateScalar(Objects.requireNonNull(lower, "lowerInclusive"));
        validateScalar(Objects.requireNonNull(upper, "upperInclusive"));
        return new SnapshotPredicate(
                (product, parameters) -> {
                    parameters.add(lower instanceof Enum<?> e ? e.name() : lower);
                    parameters.add(upper instanceof Enum<?> e ? e.name() : upper);
                    return SnapshotSource.column(column, product) + " BETWEEN ? AND ?";
                },
                Set.of(column));
    }

    public static <E, V> SnapshotPredicate between(CacheField<E, V> column, V lower, V upper) {
        return between(column.columnName(), lower, upper);
    }

    public static SnapshotPredicate inValues(String column, Collection<?> values) {
        SnapshotSource.quote(column);
        List<?> copy = List.copyOf(Objects.requireNonNull(values, "values"));
        if (copy.isEmpty()) throw new IllegalArgumentException("IN values must not be empty");
        copy.forEach(SnapshotPredicate::validateScalar);
        return new SnapshotPredicate(
                (product, parameters) -> {
                    int chunk = "Oracle".equals(product) ? 900 : copy.size();
                    StringBuilder sql = new StringBuilder("(");
                    for (int start = 0; start < copy.size(); start += chunk) {
                        if (start > 0) sql.append(" OR ");
                        sql.append(SnapshotSource.column(column, product)).append(" IN (");
                        int end = Math.min(copy.size(), start + chunk);
                        for (int i = start; i < end; i++) {
                            if (i > start) sql.append(", ");
                            sql.append('?');
                            Object value = copy.get(i);
                            parameters.add(value instanceof Enum<?> e ? e.name() : value);
                        }
                        sql.append(')');
                    }
                    return sql.append(')').toString();
                },
                Set.of(column));
    }

    public static <E, V> SnapshotPredicate inValues(CacheField<E, V> column, Collection<V> values) {
        return inValues(column.columnName(), values);
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
