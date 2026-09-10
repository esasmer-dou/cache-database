package com.reactor.cachedb.jdbc;

import java.util.List;

public final class CacheDbDatabaseProductUnsupportedException extends IllegalStateException {
    private final String databaseProductName;
    private final List<String> availableProviderIds;

    public CacheDbDatabaseProductUnsupportedException(String databaseProductName, List<String> availableProviderIds) {
        super("No CacheDB JDBC query dialect supports database product '"
                + (databaseProductName == null ? "" : databaseProductName)
                + "'. Available providers: " + List.copyOf(availableProviderIds));
        this.databaseProductName = databaseProductName == null ? "" : databaseProductName;
        this.availableProviderIds = List.copyOf(availableProviderIds);
    }

    public String databaseProductName() {
        return databaseProductName;
    }

    public List<String> availableProviderIds() {
        return availableProviderIds;
    }
}
