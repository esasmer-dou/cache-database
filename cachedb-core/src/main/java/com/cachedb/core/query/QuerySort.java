package com.reactor.cachedb.core.query;

public record QuerySort(
        String column,
        QuerySortDirection direction
) {
    public static QuerySort asc(String column) {
        return new QuerySort(column, QuerySortDirection.ASC);
    }

    public static QuerySort desc(String column) {
        return new QuerySort(column, QuerySortDirection.DESC);
    }
}
