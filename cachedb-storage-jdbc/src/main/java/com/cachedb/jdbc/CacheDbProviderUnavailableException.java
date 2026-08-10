package com.reactor.cachedb.jdbc;

import java.util.List;

public final class CacheDbProviderUnavailableException extends IllegalStateException {
    private final String providerId;
    private final List<String> availableProviders;

    public CacheDbProviderUnavailableException(String providerId, List<String> availableProviders) {
        super("CacheDB JDBC provider '" + providerId + "' is not on the classpath. Available providers: "
                + (availableProviders == null ? List.of() : availableProviders));
        this.providerId = providerId;
        this.availableProviders = availableProviders == null ? List.of() : List.copyOf(availableProviders);
    }

    public String providerId() {
        return providerId;
    }

    public List<String> availableProviders() {
        return availableProviders;
    }
}
