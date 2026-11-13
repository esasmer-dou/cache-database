package com.reactor.cachedb.spring.data;

import com.reactor.cachedb.core.query.*;
import java.util.*;

/** Typed operand assembly only. Method names are parsed exclusively by the annotation processor. */
public final class SpringDataQueries {
    public static final int MAX_IN_VALUES = 512;
    public static final int MAX_NOT_IN_VALUES = 32;
    private SpringDataQueries() {}

    public static List<Object> values(Iterable<?> input) {
        Objects.requireNonNull(input, "IN values");
        var values = new ArrayList<Object>();
        for (Object value : input) {
            if (values.size() == MAX_IN_VALUES) throw new IllegalArgumentException("IN/NotIn supports at most 512 values");
            values.add(Objects.requireNonNull(value, "IN values cannot contain null"));
        }
        return List.copyOf(values);
    }

    public static List<String> enumValues(Iterable<? extends Enum<?>> input) {
        var admitted = values(input); var names = new ArrayList<String>(admitted.size());
        for (Object value : admitted) names.add(((Enum<?>) value).name());
        return List.copyOf(names);
    }

    public static QueryNode notIn(String column, Iterable<?> input) {
        var values = values(input);
        if (values.size() > MAX_NOT_IN_VALUES)
            throw new IllegalArgumentException("NotIn supports at most 32 exclusions; core has no native NOT IN index operation");
        var nodes = new QueryNode[values.size()];
        for (int i = 0; i < values.size(); i++) nodes[i] = QueryFilter.ne(column, values.get(i));
        return QueryGroup.and(nodes);
    }

    public static QueryNode between(String column, Object lower, Object upper) {
        return QueryGroup.and(QueryFilter.gte(column, Objects.requireNonNull(lower, "lower")),
                QueryFilter.lte(column, Objects.requireNonNull(upper, "upper")));
    }

    public static QuerySpec bounded(QuerySpec query) {
        Objects.requireNonNull(query, "query");
        var pending = new ArrayDeque<QueryNode>();
        pending.add(query.rootGroup());
        int nodes = 0; long bytes = 0;
        while (!pending.isEmpty()) {
            if (++nodes > 4096) throw new IllegalArgumentException("Derived query exceeds 4096 predicate nodes");
            var node = pending.removeFirst();
            if (node instanceof QueryGroup group) {
                if (pending.size() + group.nodes().size() > 4096) throw new IllegalArgumentException("Derived query node budget exceeded");
                pending.addAll(group.nodes());
            } else if (node instanceof QueryFilter filter) {
                bytes += valueBytes(filter.value());
                if (filter.values().size() > MAX_IN_VALUES) throw new IllegalArgumentException("IN supports at most 512 values");
                for (Object value : filter.values()) bytes += valueBytes(value);
                if (bytes > 16L * 1024 * 1024) throw new IllegalArgumentException("Derived query operand budget exceeds 16 MiB");
            } else throw new IllegalArgumentException("Unknown query node");
        }
        return query;
    }

    static String scalar(Object value) {
        if (value == null) return "N:";
        String tag; String text;
        if (value instanceof String item) { tag = "S:"; text = item; }
        else if (value instanceof Boolean item) { tag = "B:"; text = item ? "1" : "0"; }
        else if (value instanceof Byte item) { tag = "Y:"; text = Byte.toString(item); }
        else if (value instanceof Short item) { tag = "H:"; text = Short.toString(item); }
        else if (value instanceof Integer item) { tag = "I:"; text = Integer.toString(item); }
        else if (value instanceof Long item) { tag = "L:"; text = Long.toString(item); }
        else if (value instanceof Double item) {
            if (!Double.isFinite(item)) throw new IllegalArgumentException("Non-finite derived numeric operand");
            tag = "D:"; text = Double.toHexString(item);
        } else if (value instanceof Float item) {
            if (!Float.isFinite(item)) throw new IllegalArgumentException("Non-finite derived numeric operand");
            tag = "F:"; text = Float.toHexString(item);
        } else if (value.getClass() == java.math.BigDecimal.class) {
            var item = com.reactor.cachedb.core.model.NumericValues.boundedDecimal((java.math.BigDecimal) value);
            tag = "M:"; text = item.toString();
        } else if (value.getClass() == java.math.BigInteger.class) {
            var item = (java.math.BigInteger) value;
            com.reactor.cachedb.core.model.NumericValues.boundedDecimal(item);
            tag = "G:"; text = item.toString(10);
        } else if (value instanceof java.util.UUID item) { tag = "U:"; text = item.toString(); }
        else if (value instanceof java.time.Instant item) { tag = "T:"; text = item.toString(); }
        else if (value instanceof java.time.LocalDate item) { tag = "A:"; text = item.toString(); }
        else if (value instanceof java.time.LocalDateTime item) { tag = "C:"; text = item.toString(); }
        else if (value instanceof java.time.OffsetDateTime item) { tag = "O:"; text = item.toString(); }
        else if (value instanceof Enum<?> item) { tag = "E:"; text = item.name(); }
        else throw new IllegalArgumentException("Unsupported derived operand type");
        if (text.length() > 4096) throw new IllegalArgumentException("Derived operand exceeds 4096 characters");
        return tag + text;
    }

    private static long valueBytes(Object value) {
        return value == null ? 0 : 3L * scalar(value).length();
    }
}
