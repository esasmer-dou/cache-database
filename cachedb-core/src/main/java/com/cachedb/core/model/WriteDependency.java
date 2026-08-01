package com.reactor.cachedb.core.model;

/**
 * A durable parent-version prerequisite for a write-behind operation.
 */
public record WriteDependency(String redisNamespace, String id, long version) {
    public WriteDependency {
        if (redisNamespace == null || redisNamespace.isBlank()) {
            throw new IllegalArgumentException("redisNamespace must not be blank");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be greater than zero");
        }
        redisNamespace = redisNamespace.trim();
        id = id.trim();
    }
}
