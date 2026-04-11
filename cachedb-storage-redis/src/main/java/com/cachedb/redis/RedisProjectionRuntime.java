package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import redis.clients.jedis.JedisPooled;

final class RedisProjectionRuntime<P, ID> {

    private final JedisPooled jedis;
    private final EntityMetadata<P, ID> metadata;
    private final EntityCodec<P> codec;
    private final RedisKeyStrategy keyStrategy;
    private final CachePolicy cachePolicy;
    private final RedisQueryIndexManager<P, ID> queryIndexManager;
    private final EntityProjectionBinding<?, P, ID> binding;

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
    }

    void delete(ID id) {
        jedis.del(payloadKey(id), tombstoneKey(id));
        queryIndexManager.removeById(id);
    }
}
