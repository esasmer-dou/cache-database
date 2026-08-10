package com.reactor.cachedb.jdbc;

import java.util.List;

public final class CacheDbProviderAmbiguousException extends IllegalStateException {
    private final List<String> availableProviders;

    public CacheDbProviderAmbiguousException(List<String> availableProviders) {
        super("Multiple CacheDB JDBC providers are available: "
                + (availableProviders == null ? List.of() : availableProviders)
                + ". Select one with cachedb.sql.provider");
        this.availableProviders = availableProviders == null ? List.of() : List.copyOf(availableProviders);
    }

    public List<String> availableProviders() {
        return availableProviders;
    }
}
