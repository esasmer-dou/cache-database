package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;

import java.util.ArrayList;
import java.util.Collection;

final class RedisProjectionRuntime<P, ID> {

    private final JedisPooled jedis;
    private final EntityMetadata<P, ID> metadata;
    private final EntityCodec<P> codec;
    private final RedisKeyStrategy keyStrategy;
    private final CachePolicy cachePolicy;
    private final RedisQueryIndexManager<P, ID> queryIndexManager;
    private final EntityProjectionBinding<?, P, ID> binding;
    private volatile Runnable queryCacheInvalidator;

    RedisProjectionRuntime(
            JedisPooled jedis,
            EntityMetadata<P, ID> metadata,
            EntityCodec<P> codec,
            RedisKeyStrategy keyStrategy,
            CachePolicy cachePolicy,
            RedisQueryIndexManager<P, ID> queryIndexManager,
            EntityProjectionBinding<?, P, ID> binding
    ) {
        this.jedis = jedis;
        this.metadata = metadata;
        this.codec = codec;
        this.keyStrategy = keyStrategy;
        this.cachePolicy = cachePolicy;
        this.queryIndexManager = queryIndexManager;
        this.binding = binding;
    }

    EntityMetadata<P, ID> metadata() {
        return metadata;
    }

    JedisPooled jedis() {
        return jedis;
    }

    EntityCodec<P> codec() {
        return codec;
    }

    RedisQueryIndexManager<P, ID> queryIndexManager() {
        return queryIndexManager;
    }

    EntityProjectionBinding<?, P, ID> binding() {
        return binding;
    }

    String sortedIndexKey(String column) {
        return keyStrategy.indexSortKey(metadata.redisNamespace(), column);
    }

    String payloadKey(ID id) {
        return keyStrategy.entityKey(metadata.redisNamespace(), id);
    }

    String payloadKey(String rawId) {
        return keyStrategy.entityKey(metadata.redisNamespace(), rawId);
    }

    String tombstoneKey(ID id) {
        return keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
    }

    String tombstoneKey(String rawId) {
        return keyStrategy.tombstoneKey(metadata.redisNamespace(), rawId);
    }

    void upsert(P projection) {
        ID id = metadata.idAccessor().apply(projection);
        String payloadKey = payloadKey(id);
        String encoded = codec.toRedisValue(projection);
        long ttlSeconds = cachePolicy.entityTtlSeconds();
        if (ttlSeconds > 0) {
            jedis.setex(payloadKey, Math.toIntExact(Math.min(Integer.MAX_VALUE, ttlSeconds)), encoded);
        } else {
            jedis.set(payloadKey, encoded);
        }
        jedis.del(tombstoneKey(id));
        queryIndexManager.reindex(projection);
        invalidateQueryCache();
    }

    void upsertBatch(Collection<P> projections) {
        if (projections == null || projections.isEmpty()) {
            return;
        }
        ArrayList<P> candidates = new ArrayList<>(projections.size());
        for (P projection : projections) {
            if (projection != null) {
                candidates.add(projection);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        long ttlSeconds = cachePolicy.entityTtlSeconds();
        int effectiveTtlSeconds = ttlSeconds > 0
                ? Math.toIntExact(Math.min(Integer.MAX_VALUE, ttlSeconds))
                : 0;
        try (Pipeline pipeline = jedis.pipelined()) {
            for (P projection : candidates) {
                ID id = metadata.idAccessor().apply(projection);
                String payloadKey = payloadKey(id);
                String encoded = codec.toRedisValue(projection);
                if (effectiveTtlSeconds > 0) {
                    pipeline.setex(payloadKey, effectiveTtlSeconds, encoded);
                } else {
                    pipeline.set(payloadKey, encoded);
                }
                pipeline.del(tombstoneKey(id));
            }
            pipeline.sync();
        }
        queryIndexManager.reindexBatch(candidates);
        invalidateQueryCache();
    }

    void upsertWarmBatch(Collection<P> projections) {
        if (projections == null || projections.isEmpty()) {
            return;
        }
        ArrayList<P> candidates = new ArrayList<>(projections.size());
        for (P projection : projections) {
            if (projection != null) {
                candidates.add(projection);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        long ttlSeconds = cachePolicy.entityTtlSeconds();
        int effectiveTtlSeconds = ttlSeconds > 0
                ? Math.toIntExact(Math.min(Integer.MAX_VALUE, ttlSeconds))
                : 0;
        try (Pipeline pipeline = jedis.pipelined()) {
            for (P projection : candidates) {
                ID id = metadata.idAccessor().apply(projection);
                String payloadKey = payloadKey(id);
                String encoded = codec.toRedisValue(projection);
                if (effectiveTtlSeconds > 0) {
                    pipeline.setex(payloadKey, effectiveTtlSeconds, encoded);
                } else {
                    pipeline.set(payloadKey, encoded);
                }
                pipeline.del(tombstoneKey(id));
            }
            pipeline.sync();
        }
        queryIndexManager.reindexProjectionWarmBatch(candidates);
        invalidateQueryCache();
    }

    void delete(ID id) {
        jedis.del(payloadKey(id), tombstoneKey(id));
        queryIndexManager.removeById(id);
        invalidateQueryCache();
    }

    void attachQueryCacheInvalidator(Runnable invalidator) {
        this.queryCacheInvalidator = invalidator;
    }

    private void invalidateQueryCache() {
        Runnable invalidator = queryCacheInvalidator;
        if (invalidator == null) {
            return;
        }
        try {
            invalidator.run();
        } catch (RuntimeException ignored) {
        }
    }
}
