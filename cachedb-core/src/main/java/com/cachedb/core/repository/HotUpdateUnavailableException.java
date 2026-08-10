package com.reactor.cachedb.core.repository;

/**
 * Raised when a partial update cannot prove the current version from Redis.
 * It never means the durable SQL row is absent.
 */
public final class HotUpdateUnavailableException extends IllegalStateException {
    private final Object id;

    public HotUpdateUnavailableException(Object id) {
        super("Partial update requires the complete current entity and version in Redis for id=" + id
                + ". Warm the entity or use an explicit full-entity/source command; CacheDB will not auto-merge from SQL.");
        this.id = id;
    }

    public Object id() {
        return id;
    }
}
