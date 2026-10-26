package com.reactor.cachedb.core.query;

import java.util.Objects;

/** Aggregates the complete bounded matching active set, not a paged query slice or SQL history. */
public record AggregateSpec(AggregateFunction function, String fieldName, QueryNode filter) {
    public AggregateSpec {
        Objects.requireNonNull(function, "function");
        if (function != AggregateFunction.COUNT && (fieldName == null || fieldName.isBlank()))
            throw new IllegalArgumentException("Numeric aggregate requires a field");
    }
    public static AggregateSpec count(QueryNode filter) { return new AggregateSpec(AggregateFunction.COUNT, null, filter); }
    public static AggregateSpec sum(String field, QueryNode filter) { return new AggregateSpec(AggregateFunction.SUM, field, filter); }
    public static AggregateSpec min(String field, QueryNode filter) { return new AggregateSpec(AggregateFunction.MIN, field, filter); }
    public static AggregateSpec max(String field, QueryNode filter) { return new AggregateSpec(AggregateFunction.MAX, field, filter); }
    public static AggregateSpec avg(String field, QueryNode filter) { return new AggregateSpec(AggregateFunction.AVG, field, filter); }
    public static <T> AggregateSpec sum(TypedColumn<T, ? extends Number> field, QueryNode filter) { return sum(field.columnName(), filter); }
    public static <T> AggregateSpec min(TypedColumn<T, ? extends Number> field, QueryNode filter) { return min(field.columnName(), filter); }
    public static <T> AggregateSpec max(TypedColumn<T, ? extends Number> field, QueryNode filter) { return max(field.columnName(), filter); }
    public static <T> AggregateSpec avg(TypedColumn<T, ? extends Number> field, QueryNode filter) { return avg(field.columnName(), filter); }
}
