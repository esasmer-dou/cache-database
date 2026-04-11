package com.reactor.cachedb.core.query;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

public final class QueryEvaluator {

    public boolean matches(Map<String, Object> columns, QueryNode node) {
        if (node instanceof QueryFilter filter) {
            return matches(columns, filter);
        }
        QueryGroup group = (QueryGroup) node;
        return switch (group.operator()) {
            case AND -> group.nodes().stream().allMatch(child -> matches(columns, child));
            case OR -> group.nodes().stream().anyMatch(child -> matches(columns, child));
        };
    }

    public boolean matches(Map<String, Object> columns, QueryFilter filter) {
        Object left = columns.get(filter.column());
        return switch (filter.operator()) {
            case EQ -> Objects.equals(left, filter.value());
            case NE -> !Objects.equals(left, filter.value());
            case GT -> compare(left, filter.value()) > 0;
            case GTE -> compare(left, filter.value()) >= 0;
            case LT -> compare(left, filter.value()) < 0;
            case LTE -> compare(left, filter.value()) <= 0;
            case IN -> filter.values().contains(left);
            case CONTAINS -> left instanceof String stringValue && filter.value() != null && stringValue.contains(String.valueOf(filter.value()));
            case STARTS_WITH -> left instanceof String stringValue && filter.value() != null && stringValue.startsWith(String.valueOf(filter.value()));
        };
    }

    public Comparator<Map<String, Object>> comparator(QuerySort sort) {
        Comparator<Map<String, Object>> comparator = Comparator.comparing(
                map -> comparableValue(map.get(sort.column())),
                Comparator.nullsLast(Comparator.naturalOrder())
        );
        return sort.direction() == QuerySortDirection.DESC ? comparator.reversed() : comparator;
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
