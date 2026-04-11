package com.reactor.cachedb.core.query;

import java.util.List;

public record QueryFilter(
        String column,
        QueryOperator operator,
        Object value,
        List<Object> values
) implements QueryNode {
    public static QueryFilter eq(String column, Object value) {
        return new QueryFilter(column, QueryOperator.EQ, value, List.of());
    }

    public static QueryFilter ne(String column, Object value) {
        return new QueryFilter(column, QueryOperator.NE, value, List.of());
    }

    public static QueryFilter gt(String column, Object value) {
        return new QueryFilter(column, QueryOperator.GT, value, List.of());
    }

    public static QueryFilter gte(String column, Object value) {
        return new QueryFilter(column, QueryOperator.GTE, value, List.of());
    }

    public static QueryFilter lt(String column, Object value) {
        return new QueryFilter(column, QueryOperator.LT, value, List.of());
    }

    public static QueryFilter lte(String column, Object value) {
        return new QueryFilter(column, QueryOperator.LTE, value, List.of());
    }

    public static QueryFilter in(String column, List<Object> values) {
        return new QueryFilter(column, QueryOperator.IN, null, List.copyOf(values));
    }

    public static QueryFilter contains(String column, Object value) {
        return new QueryFilter(column, QueryOperator.CONTAINS, value, List.of());
    }

    public static QueryFilter startsWith(String column, Object value) {
        return new QueryFilter(column, QueryOperator.STARTS_WITH, value, List.of());
    }
}
