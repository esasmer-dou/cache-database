package com.reactor.cachedb.core.query;

import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.repository.SourceWindow;
import com.reactor.cachedb.core.repository.WindowCursor;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.core.route.RouteCoverage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Stable lexicographic keyset pagination shared by generated hot and source routes. */
public final class KeysetPagination {
    private KeysetPagination() {
    }

    public static QuerySpec apply(QuerySpec base, WindowRequest request, List<QuerySort> stableSorts) {
        return apply(base, request, stableSorts, null, null);
    }

    public static QuerySpec apply(
            QuerySpec base,
            WindowRequest request,
            List<QuerySort> stableSorts,
            String routeName,
            String scope
    ) {
        QuerySpec normalized = base == null ? QuerySpec.builder().build() : base;
        WindowRequest window = request == null ? WindowRequest.first(Math.min(100, normalized.limit())) : request;
        requireStableSorts(stableSorts);
        QuerySpec result = normalized.orderBy(stableSorts.toArray(QuerySort[]::new)).limitTo(window.queryLimit());
        if (window.after() == null) {
            return result;
        }
        Map<String, Object> cursor = routeName == null
                ? WindowCursor.decode(window.after())
                : WindowCursor.decode(window.after(), cursorContract(routeName, scope, stableSorts));
        ArrayList<QueryNode> alternatives = new ArrayList<>(stableSorts.size());
        for (int current = 0; current < stableSorts.size(); current++) {
            ArrayList<QueryNode> conjunction = new ArrayList<>(current + 1);
            for (int prior = 0; prior < current; prior++) {
                QuerySort sort = stableSorts.get(prior);
                conjunction.add(QueryFilter.eq(sort.column(), requiredCursorValue(cursor, sort.column())));
            }
            QuerySort sort = stableSorts.get(current);
            Object value = requiredCursorValue(cursor, sort.column());
            conjunction.add(sort.direction() == QuerySortDirection.DESC
                    ? QueryFilter.lt(sort.column(), value)
                    : QueryFilter.gt(sort.column(), value));
            alternatives.add(QueryGroup.and(conjunction.toArray(QueryNode[]::new)));
        }
        return result.and(QueryGroup.or(alternatives.toArray(QueryNode[]::new)));
    }

    public static <T> HotWindow<T> hotWindow(
            List<T> rawItems,
            WindowRequest request,
            List<QuerySort> stableSorts,
            Function<T, Map<String, Object>> columns,
            RouteCoverage coverage
    ) {
        Slice<T> slice = slice(rawItems, request, stableSorts, columns, null);
        return new HotWindow<>(slice.items(), slice.nextCursor(), coverage);
    }

    public static <T> HotWindow<T> hotWindow(
            List<T> rawItems,
            WindowRequest request,
            List<QuerySort> stableSorts,
            Function<T, Map<String, Object>> columns,
            RouteCoverage coverage,
            String routeName,
            String scope
    ) {
        Slice<T> slice = slice(
                rawItems,
                request,
                stableSorts,
                columns,
                cursorContract(routeName, scope, stableSorts)
        );
        return new HotWindow<>(slice.items(), slice.nextCursor(), coverage);
    }

    public static <T> SourceWindow<T> sourceWindow(
            List<T> rawItems,
            WindowRequest request,
            List<QuerySort> stableSorts,
            Function<T, Map<String, Object>> columns
    ) {
        Slice<T> slice = slice(rawItems, request, stableSorts, columns, null);
        return new SourceWindow<>(slice.items(), slice.nextCursor());
    }

    public static <T> SourceWindow<T> sourceWindow(
            List<T> rawItems,
            WindowRequest request,
            List<QuerySort> stableSorts,
            Function<T, Map<String, Object>> columns,
            String routeName,
            String scope
    ) {
        Slice<T> slice = slice(
                rawItems,
                request,
                stableSorts,
                columns,
                cursorContract(routeName, scope, stableSorts)
        );
        return new SourceWindow<>(slice.items(), slice.nextCursor());
    }

    private static <T> Slice<T> slice(
            List<T> rawItems,
            WindowRequest request,
            List<QuerySort> stableSorts,
            Function<T, Map<String, Object>> columns,
            String cursorContract
    ) {
        WindowRequest window = request == null ? WindowRequest.first(100) : request;
        requireStableSorts(stableSorts);
        List<T> safe = rawItems == null ? List.of() : rawItems;
        boolean hasMore = safe.size() > window.limit();
        List<T> items = List.copyOf(safe.subList(0, Math.min(window.limit(), safe.size())));
        if (!hasMore || items.isEmpty()) {
            return new Slice<>(items, null);
        }
        Map<String, Object> values = columns.apply(items.get(items.size() - 1));
        LinkedHashMap<String, Object> cursorValues = new LinkedHashMap<>(stableSorts.size());
        for (QuerySort sort : stableSorts) {
            if (!values.containsKey(sort.column())) {
                throw new IllegalStateException("Cursor extractor did not provide sort column " + sort.column());
            }
            cursorValues.put(sort.column(), values.get(sort.column()));
        }
        String nextCursor = cursorContract == null
                ? WindowCursor.encode(cursorValues)
                : WindowCursor.encode(cursorValues, cursorContract);
        return new Slice<>(items, nextCursor);
    }

    private static Object requiredCursorValue(Map<String, Object> values, String column) {
        if (!values.containsKey(column)) {
            throw new IllegalArgumentException("Cursor does not contain stable sort column " + column);
        }
        Object value = values.get(column);
        if (value == null) {
            throw new IllegalArgumentException("Null keyset values are not supported for sort column " + column);
        }
        return value;
    }

    private static void requireStableSorts(List<QuerySort> stableSorts) {
        if (stableSorts == null || stableSorts.isEmpty()) {
            throw new IllegalArgumentException("Keyset pagination requires at least one stable sort");
        }
    }

    private static String cursorContract(String routeName, String scope, List<QuerySort> stableSorts) {
        if (routeName == null || routeName.isBlank()) {
            throw new IllegalArgumentException("routeName must not be blank for a contract-aware cursor");
        }
        String normalizedScope = scope == null || scope.isBlank() ? "global" : scope;
        StringBuilder contract = new StringBuilder(routeName.length() + normalizedScope.length() + 64);
        appendPart(contract, routeName);
        appendPart(contract, normalizedScope);
        for (QuerySort sort : stableSorts) {
            appendPart(contract, sort.column());
            appendPart(contract, sort.direction().name());
        }
        return contract.toString();
    }

    private static void appendPart(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private record Slice<T>(List<T> items, String nextCursor) {
    }
}
