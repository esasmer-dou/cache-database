package com.reactor.cachedb.core.query;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

public final class QueryEvaluator {

    public <T> boolean matches(T entity, com.reactor.cachedb.core.model.EntityCodec<T> codec, QueryNode node) {
        if (!codec.directColumnAccess()) return matches(codec.toColumns(entity), node);
        return matchesDirect(entity, codec, node);
    }

    private <T> boolean matchesDirect(T entity, com.reactor.cachedb.core.model.EntityCodec<T> codec, QueryNode node) {
        if (node instanceof QueryFilter filter) return matchesValue(codec.entityColumnValue(entity, filter.column()), filter);
        QueryGroup group = (QueryGroup) node;
        boolean and = Objects.requireNonNull(group.operator(), "operator") == QueryGroupOperator.AND;
        for (QueryNode child : group.nodes())
            if (matchesDirect(entity, codec, child) != and) return !and;
        return and;
    }

    public <T> int compareColumns(T left, T right, com.reactor.cachedb.core.model.EntityCodec<T> codec, QuerySort sort) {
        if (sort.direction() == QuerySortDirection.DESC) { T swap = left; left = right; right = swap; }
        return compareSortValues(codec.entityColumnValue(left, sort.column()), codec.entityColumnValue(right, sort.column()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareSortValues(Object left, Object right) {
        Comparable a = comparableValue(left);
        Comparable b = comparableValue(right);
        if (a == null) return b == null ? 0 : 1;
        return b == null ? -1 : a.compareTo(b);
    }

    public boolean matches(Map<String, Object> columns, QueryNode node) {
        if (node instanceof QueryFilter filter) {
            return matches(columns, filter);
        }
        QueryGroup group = (QueryGroup) node;
        boolean and =
                Objects.requireNonNull(group.operator(), "operator") == QueryGroupOperator.AND;
        for (QueryNode child : group.nodes()) {
            if (matches(columns, child) != and) return !and;
        }
        return and;
    }

    public boolean matches(Map<String, Object> columns, QueryFilter filter) {
        return matchesValue(columns.get(filter.column()), filter);
    }

    private boolean matchesValue(Object left, QueryFilter filter) {
        return switch (filter.operator()) {
            case EQ -> Objects.equals(left, filter.value());
            case NE -> !Objects.equals(left, filter.value());
            case GT -> compare(left, filter.value()) > 0;
            case GTE -> compare(left, filter.value()) >= 0;
            case LT -> compare(left, filter.value()) < 0;
            case LTE -> compare(left, filter.value()) <= 0;
            case IN -> filter.values().contains(left);
            case CONTAINS ->
                    left instanceof String stringValue
                            && filter.value() != null
                            && stringValue.contains(String.valueOf(filter.value()));
            case STARTS_WITH ->
                    left instanceof String stringValue
                            && filter.value() != null
                            && stringValue.startsWith(String.valueOf(filter.value()));
        };
    }

    public Comparator<Map<String, Object>> comparator(QuerySort sort) {
        return new ColumnComparator(sort);
    }

    private final class ColumnComparator implements Comparator<Map<String, Object>> {
        private final QuerySort sort;

        private ColumnComparator(QuerySort sort) {
            this.sort = Objects.requireNonNull(sort, "sort");
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public int compare(Map<String, Object> left, Map<String, Object> right) {
            if (sort.direction() == QuerySortDirection.DESC) {
                Map<String, Object> swap = left;
                left = right;
                right = swap;
            }
            Comparable a = comparableValue(left.get(sort.column()));
            Comparable b = comparableValue(right.get(sort.column()));
            if (a == null) return b == null ? 0 : 1;
            return b == null ? -1 : a.compareTo(b);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compare(Object left, Object right) {
        Comparable comparableLeft = comparableValue(left);
        Comparable comparableRight = comparableValue(right);
        if (comparableLeft == null && comparableRight == null) {
            return 0;
        }
        if (comparableLeft == null) {
            return -1;
        }
        if (comparableRight == null) {
            return 1;
        }
        return comparableLeft.compareTo(comparableRight);
    }

    @SuppressWarnings("rawtypes")
    private Comparable comparableValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Comparable comparable) {
            return comparable;
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        return String.valueOf(value);
    }
}
