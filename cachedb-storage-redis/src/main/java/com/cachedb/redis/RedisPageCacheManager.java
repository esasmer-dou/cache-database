package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RedisPageCacheManager<T, ID> {

    private final JedisPooled jedis;
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final CachePolicy cachePolicy;
    private final RedisKeyStrategy keyStrategy;
    private final RedisHotSetManager hotSetManager;
    private final RedisQueryIndexManager<T, ID> queryIndexManager;
    private final RedisProducerGuard producerGuard;

    public RedisPageCacheManager(
            JedisPooled jedis,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RedisKeyStrategy keyStrategy,
            RedisHotSetManager hotSetManager,
            RedisQueryIndexManager<T, ID> queryIndexManager,
            RedisProducerGuard producerGuard
    ) {
        this.jedis = jedis;
        this.metadata = metadata;
        this.codec = codec;
        this.cachePolicy = cachePolicy;
        this.keyStrategy = keyStrategy;
        this.hotSetManager = hotSetManager;
        this.queryIndexManager = queryIndexManager;
        this.producerGuard = producerGuard;
    }

    public void recordEntityAccess(ID id) {
        if (producerGuard != null && producerGuard.shouldShedHotSetTracking(metadata.redisNamespace())) {
            return;
        }
        List<String> evictedIds = hotSetManager.recordAccess(metadata.redisNamespace(), id, effectiveCachePolicy());
        for (String evictedId : evictedIds) {
            jedis.del(keyStrategy.entityKey(metadata.redisNamespace(), evictedId));
            queryIndexManager.removeById(evictedId);
        }
    }

    public void cacheEntity(T entity, String encoded) {
        ID id = metadata.idAccessor().apply(entity);
        String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
        CachePolicy effectiveCachePolicy = effectiveCachePolicy();
        if (effectiveCachePolicy.entityTtlSeconds() > 0) {
            jedis.setex(entityKey, effectiveCachePolicy.entityTtlSeconds(), encoded);
        } else {
            jedis.set(entityKey, encoded);
        }
        queryIndexManager.reindex(entity);
        recordEntityAccess(id);
    }

    public void removeEntity(ID id) {
        jedis.del(keyStrategy.entityKey(metadata.redisNamespace(), id));
        hotSetManager.remove(metadata.redisNamespace(), id);
        queryIndexManager.removeById(id);
    }

    public Optional<List<T>> getCachedPage(PageWindow pageWindow) {
        List<String> ids = jedis.lrange(keyStrategy.pageKey(metadata.redisNamespace(), pageWindow.pageNumber()), 0, -1);
        if (ids == null || ids.isEmpty()) {
            return Optional.empty();
        }

        List<String> keys = ids.stream()
                .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                .toList();
        List<String> tombstoneKeys = ids.stream()
                .map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id))
                .toList();
        List<String> encodedValues = jedis.mget(keys.toArray(String[]::new));
        List<String> tombstones = jedis.mget(tombstoneKeys.toArray(String[]::new));
        List<T> entities = new ArrayList<>(encodedValues.size());
        for (int index = 0; index < encodedValues.size(); index++) {
            String encoded = encodedValues.get(index);
            if (encoded != null && tombstones.get(index) == null) {
                entities.add(codec.fromRedisValue(encoded));
                recordEntityAccess((ID) ids.get(index));
            }
        }
        return Optional.of(entities);
    }

    public void cachePage(PageWindow pageWindow, List<T> entities) {
        if (producerGuard != null && producerGuard.shouldShedPageCacheWrites(metadata.redisNamespace())) {
            return;
        }
        String pageKey = keyStrategy.pageKey(metadata.redisNamespace(), pageWindow.pageNumber());
        jedis.del(pageKey);
        if (entities.isEmpty()) {
            return;
        }

        List<String> ids = new ArrayList<>(entities.size());
        for (T entity : entities) {
            ID id = metadata.idAccessor().apply(entity);
            ids.add(String.valueOf(id));
            cacheEntity(entity, codec.toRedisValue(entity));
        }

        jedis.rpush(pageKey, ids.toArray(String[]::new));
        CachePolicy effectiveCachePolicy = effectiveCachePolicy();
        if (effectiveCachePolicy.pageTtlSeconds() > 0) {
            jedis.expire(pageKey, effectiveCachePolicy.pageTtlSeconds());
        }
    }

    public List<String> hotEntityIds() {
        CachePolicy effectiveCachePolicy = effectiveCachePolicy();
        return jedis.zrevrange(keyStrategy.hotSetKey(metadata.redisNamespace()), 0, Math.max(0, effectiveCachePolicy.hotEntityLimit() - 1));
    }

    private CachePolicy effectiveCachePolicy() {
        return producerGuard == null ? cachePolicy : producerGuard.effectiveCachePolicy(cachePolicy);
    }
}
