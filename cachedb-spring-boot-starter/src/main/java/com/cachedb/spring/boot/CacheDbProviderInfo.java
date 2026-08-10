package com.reactor.cachedb.spring.boot;

import java.util.List;

public record CacheDbProviderInfo(String id, String dialectType, List<String> availableProviders) {
    public CacheDbProviderInfo {
        availableProviders = availableProviders == null ? List.of() : List.copyOf(availableProviders);
    }
}
