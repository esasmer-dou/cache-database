package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.queue.QueryIndexRebuildResult;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;

public final class RedisIndexMaintenance {

    private final JedisPooled jedis;
    private final EntityRegistry entityRegistry;
    private final RedisKeyStrategy keyStrategy;
    private final QueryIndexConfig queryIndexConfig;
    private final RelationConfig relationConfig;
    private final QueryEvaluator queryEvaluator;

    public RedisIndexMaintenance(
            JedisPooled jedis,
            EntityRegistry entityRegistry,
            RedisKeyStrategy keyStrategy,
            QueryIndexConfig queryIndexConfig,
            RelationConfig relationConfig,
            QueryEvaluator queryEvaluator
    ) {
        this.jedis = jedis;
        this.entityRegistry = entityRegistry;
        this.keyStrategy = keyStrategy;
        this.queryIndexConfig = queryIndexConfig;
        this.relationConfig = relationConfig;
        this.queryEvaluator = queryEvaluator;
    }

    public List<QueryIndexRebuildResult> rebuildAll(String note) {
        ArrayList<QueryIndexRebuildResult> results = new ArrayList<>();
        for (EntityBinding<?, ?> binding : entityRegistry.all()) {
            results.add(rebuildEntity(binding.metadata().entityName(), note));
        }
        return List.copyOf(results);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public QueryIndexRebuildResult rebuildEntity(String entityName, String note) {
        EntityBinding<?, ?> binding = entityRegistry.find(entityName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity: " + entityName));
        RedisQueryIndexManager manager = new RedisQueryIndexManager(
                jedis,
                binding.metadata(),
                binding.codec(),
                keyStrategy,
                entityRegistry,
                queryIndexConfig,
                relationConfig,
                queryEvaluator,
                null
        );
        return manager.rebuildFromEntityStore(false, note);
    }

    public List<String> degradedEntities() {
        ArrayList<String> degraded = new ArrayList<>();
        for (EntityBinding<?, ?> binding : entityRegistry.all()) {
            if (jedis.exists(keyStrategy.indexDegradedKey(binding.metadata().redisNamespace()))) {
                degraded.add(binding.metadata().entityName());
            }
        }
        return List.copyOf(degraded);
    }
}
