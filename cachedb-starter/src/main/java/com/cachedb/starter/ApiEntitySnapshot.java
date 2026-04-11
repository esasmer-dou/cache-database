package com.reactor.cachedb.starter;

public record ApiEntitySnapshot(
        String entityName,
        String tableName,
        int columnCount,
        int hotEntityLimit,
        int pageSize,
        long entityTtlSeconds,
        long pageTtlSeconds,
        boolean relationPreloadEnabled,
        boolean pageLoaderEnabled
) {
}
