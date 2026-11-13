package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;

import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.Collection;

final class RedisProjectionRuntime<P, ID> {

    private final RedisClientOperations jedis;
    private final EntityMetadata<P, ID> metadata;
    private final EntityCodec<P> codec;
    private final RedisKeyStrategy keyStrategy;
    private final CachePolicy cachePolicy;
    private final RedisQueryIndexManager<P, ID> queryIndexManager;
    private final EntityProjectionBinding<?, P, ID> binding;
    private volatile Runnable queryCacheInvalidator;
    private volatile boolean purgeQueryCacheOwnershipAmbiguous;
    private RedisProjectionRefreshQueue asyncQueue;

    RedisProjectionRuntime<P,ID> withAsyncQueue(RedisProjectionRefreshQueue queue) { asyncQueue=queue;return this; }

    private boolean enqueueAsync(Collection<P> projections) {
        if (!binding.projection().refreshMode().isAsync()) return false;
        if (asyncQueue==null) throw new IllegalStateException("Async projection requires its durable refresh queue");
        if (projections.size()>10_000) throw new IllegalArgumentException("Async projection warm limit exceeds 10000 rows");
        var ids=new ArrayList<ID>();
        for (P projection:projections) if (projection!=null) ids.add(metadata.idAccessor().apply(projection));
        var checked=RedisBulkDeleteExecutor.copyIds(ids);
        for (int start=0;start<checked.size();start+=128)
            asyncQueue.enqueueUpsertBatch(binding.entityName(),java.util.List.of(binding.projection().name()),
                    checked.subList(start,Math.min(checked.size(),start+128)));
        return true;
    }

    RedisProjectionRuntime(
            JedisPooled jedis,
            EntityMetadata<P, ID> metadata,
            EntityCodec<P> codec,
            RedisKeyStrategy keyStrategy,
            CachePolicy cachePolicy,
            RedisQueryIndexManager<P, ID> queryIndexManager,
            EntityProjectionBinding<?, P, ID> binding) {
        this(
                JedisPooledOperations.of(jedis),
                metadata,
                codec,
                keyStrategy,
                cachePolicy,
                queryIndexManager,
                binding);
    }

    RedisProjectionRuntime(
            RedisClientOperations jedis,
            EntityMetadata<P, ID> metadata,
            EntityCodec<P> codec,
            RedisKeyStrategy keyStrategy,
            CachePolicy cachePolicy,
            RedisQueryIndexManager<P, ID> queryIndexManager,
            EntityProjectionBinding<?, P, ID> binding) {
        this.jedis = jedis;
        com.reactor.cachedb.core.protection.TenantProtectionGate.requireLegacy(metadata);
        this.metadata = metadata;
        this.codec = codec;
        this.keyStrategy = keyStrategy;
        this.cachePolicy = cachePolicy;
        this.queryIndexManager = queryIndexManager;
        this.binding = binding;
    }

    /** Caller must attest the complete schema and a same-source-ID, single-owner projection contract. */
    RedisPurgeTarget purgeTarget(boolean reviewedIdentityPreserving, boolean mixedOwnership) {
        if (mixedOwnership || !reviewedIdentityPreserving || purgeQueryCacheOwnershipAmbiguous) throw RedisIdentityPurgeParticipant.incomplete();
        return new RedisPurgeTarget(queryIndexManager.purgeLayout(), java.util.List.of(), null, true, true,
                id -> {
                    if (purgeQueryCacheOwnershipAmbiguous) throw RedisIdentityPurgeParticipant.incomplete();
                    queryIndexManager.purgeInvalidateLocalStatistics();
                    Runnable invalidator = queryCacheInvalidator;
                    if (invalidator != null) invalidator.run();
                });
    }

    EntityMetadata<P, ID> metadata() {
        return metadata;
    }

    RedisClientOperations jedis() {
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
        if (enqueueAsync(java.util.List.of(projection))) return;
        ID id = metadata.idAccessor().apply(projection);
        String payloadKey = payloadKey(id);
        String encoded = codec.toRedisValue(projection);
        long ttlSeconds = cachePolicy.entityTtlSeconds();
        if (ttlSeconds > 0) {
            jedis.commands()
                    .setex(
                            payloadKey,
                            Math.toIntExact(Math.min(Integer.MAX_VALUE, ttlSeconds)),
                            encoded);
        } else {
            jedis.commands().set(payloadKey, encoded);
        }
        jedis.commands().del(tombstoneKey(id));
        queryIndexManager.reindex(projection);
        invalidateQueryCache();
    }

    void upsertBatch(Collection<P> projections) {
        if (projections == null || projections.isEmpty()) {
            return;
        }
        if (enqueueAsync(projections)) return;
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
        int effectiveTtlSeconds =
                ttlSeconds > 0 ? Math.toIntExact(Math.min(Integer.MAX_VALUE, ttlSeconds)) : 0;
        try (AbstractPipeline pipeline = jedis.pipeline()) {
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
        if (enqueueAsync(projections)) return;
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
        int effectiveTtlSeconds =
                ttlSeconds > 0 ? Math.toIntExact(Math.min(Integer.MAX_VALUE, ttlSeconds)) : 0;
        try (AbstractPipeline pipeline = jedis.pipeline()) {
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    boolean reconcile(String rawId, EntityMetadata<?,?> parentMetadata,
            RedisProjectionMutationFence.Parent parent, P projection) {
        com.reactor.cachedb.core.protection.TenantProtectionGate.requireLegacy(parentMetadata);
        if (projection != null && !rawId.equals(String.valueOf(metadata.idAccessor().apply(projection))))
            throw new IllegalArgumentException("Async projection must preserve the parent identity");
        return queryIndexManager.withCompositeMutation(() -> {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            var prior=((RedisBulkDeleteExecutor.State)RedisBulkDeleteExecutor.states(jedis,keyStrategy,(EntityMetadata)metadata,
                    java.util.List.of(rawId),false,deadline).get(0)).meta();
            var commands=new ArrayList<java.util.List<String>>();
            if (projection == null) {
                commands.addAll(queryIndexManager.bulkDeleteCommands(rawId,prior));
                commands.add(java.util.List.of("DEL",payloadKey(rawId)));
                commands.add(java.util.List.of("DEL",tombstoneKey(rawId)));
            } else {
                try (var buffer=new RedisProjectionCommandBuffer()) {
                    String encoded=java.util.Objects.requireNonNull(codec.toRedisValue(projection),"projection payload");
                    if (com.reactor.cachedb.core.codec.Utf8Length.of(encoded)>RedisProjectionCommandBuffer.MAX_BYTES)
                        throw new IllegalArgumentException("Projection payload exceeds 16 MiB before transport encoding");
                    long ttl=cachePolicy.entityTtlSeconds();
                    if (ttl>0) buffer.setex(payloadKey(rawId),Math.toIntExact(Math.min(Integer.MAX_VALUE,ttl)),encoded);
                    else buffer.set(payloadKey(rawId),encoded);
                    buffer.del(tombstoneKey(rawId));
                    commands.addAll(buffer.commands());
                }
                commands.addAll(queryIndexManager.projectionUpsertCommands(projection,prior,parent.owner()));
            }
            RedisBulkDeleteExecutor.checkDeadline(deadline);
            boolean applied=RedisProjectionMutationFence.commit(jedis,keyStrategy,parentMetadata,rawId,parent,commands);
            if (applied) { queryIndexManager.bulkDeleteObserved();invalidateQueryCache(); }
            return applied;
        });
    }

    void deleteBatch(java.util.Map<ID,Long> versions, String parentNamespace, long deadline) {
        if (versions.isEmpty()) return;
        RedisBulkDeleteExecutor.removeProjection(jedis, metadata, keyStrategy, queryIndexManager,
                versions, parentNamespace, deadline);
        invalidateQueryCache();
    }

    void delete(ID id) {
        if (binding.projection().refreshMode().isAsync()) {
            if (asyncQueue==null) throw new IllegalStateException("Async projection requires its durable refresh queue");
            asyncQueue.enqueueDelete(binding.entityName(),binding.projection().name(),id);return;
        }
        jedis.commands().del(payloadKey(id), tombstoneKey(id));
        queryIndexManager.removeById(id);
        invalidateQueryCache();
    }

    synchronized void attachQueryCacheInvalidator(Runnable invalidator) {
        if (queryCacheInvalidator != null && queryCacheInvalidator != invalidator)
            purgeQueryCacheOwnershipAmbiguous = true;
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
