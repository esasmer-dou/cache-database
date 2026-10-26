package com.reactor.cachedb.core.watch;

public record CacheChangeEvent<T, ID>(String entityName, ID id, CacheChangeType changeType,
        T previousEntity, T currentEntity, long timestampEpochMs) {}
