package com.reactor.cachedb.core.api;

/** One bounded Redis acceptance unit, followed by one asynchronous SQL transaction. */
public interface CacheWriteBatch {
    <T> CacheWriteBatch put(T entity);

    <T> CacheWriteBatch put(T entity, long expectedVersion);

    <T, ID> CacheWriteBatch delete(Class<T> entityType, ID id);

    CacheWriteBatchReceipt commit();
}
