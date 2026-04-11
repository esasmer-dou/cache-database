package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.queue.WriteBehindQueue;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

import java.util.Map;

public final class RedisWriteBehindQueue implements WriteBehindQueue {

    private final JedisPooled jedis;
    private final String streamKey;
    private final String compactionStreamKey;
    private final RedisWriteOperationMapper mapper;
    private final int maxColumnsPerOperation;
    private final WriteBehindConfig config;
    private final RedisGuardrailConfig guardrailConfig;
    private final RedisKeyStrategy keyStrategy;

    public RedisWriteBehindQueue(
            JedisPooled jedis,
            String streamKey,
            String compactionStreamKey,
            RedisWriteOperationMapper mapper,
            int maxColumnsPerOperation,
            WriteBehindConfig config,
            RedisGuardrailConfig guardrailConfig,
            RedisKeyStrategy keyStrategy
    ) {
        this.jedis = jedis;
        this.streamKey = streamKey;
        this.compactionStreamKey = compactionStreamKey;
        this.mapper = mapper;
        this.maxColumnsPerOperation = maxColumnsPerOperation;
        this.config = config;
        this.guardrailConfig = guardrailConfig;
        this.keyStrategy = keyStrategy;
    }

    public RedisWriteBehindQueue(
            JedisPooled jedis,
            String streamKey,
            RedisWriteOperationMapper mapper,
            int maxColumnsPerOperation
    ) {
        this(
                jedis,
                streamKey,
                streamKey + ":compaction",
                mapper,
                maxColumnsPerOperation,
                WriteBehindConfig.defaults(),
                RedisGuardrailConfig.defaults(),
                new RedisKeyStrategy()
        );
    }

    @Override
    public <T, ID> void enqueue(WriteOperation<T, ID> operation) {
        if (operation.columns().size() > maxColumnsPerOperation) {
            throw new IllegalArgumentException(
                    "Operation column count " + operation.columns().size()
                            + " exceeds configured limit " + maxColumnsPerOperation
            );
        }
        enforceHardLimits(operation);
        var body = mapper.toBody(operation);
        jedis.xadd(streamKey, XAddParams.xAddParams(), body);
        if (!config.durableCompactionEnabled()) {
            return;
        }
        String targetCompactionStreamKey = keyStrategy.compactionStreamKey(
                compactionStreamKey,
                operation.metadata().redisNamespace(),
                operation.id(),
                config.compactionShardCount()
        );
        String payloadKey = keyStrategy.compactionPayloadKey(operation.metadata().redisNamespace(), operation.id());
        String pendingKey = keyStrategy.compactionPendingKey(operation.metadata().redisNamespace(), operation.id());
        String statsKey = keyStrategy.compactionStatsKey();
        boolean payloadExists = jedis.exists(payloadKey);
        jedis.hset(payloadKey, body);
        if (!payloadExists) {
            jedis.hincrBy(statsKey, "payloadCount", 1L);
        }
        expireIfConfigured(payloadKey, guardrailConfig.compactionPayloadTtlSeconds());
        if (jedis.setnx(pendingKey, String.valueOf(operation.version())) == 1L) {
            jedis.hincrBy(statsKey, "pendingCount", 1L);
            jedis.xadd(targetCompactionStreamKey, compactionAddParams(), Map.of(
                    "namespace", operation.metadata().redisNamespace(),
                    "id", String.valueOf(operation.id()),
                    "version", String.valueOf(operation.version()),
                    "entity", operation.metadata().entityName()
            ));
        } else {
            jedis.set(pendingKey, String.valueOf(operation.version()));
        }
        expireIfConfigured(pendingKey, guardrailConfig.compactionPendingTtlSeconds());
    }

    private XAddParams compactionAddParams() {
        XAddParams params = XAddParams.xAddParams();
        if (config.compactionMaxLength() > 0) {
            params.maxLen(config.compactionMaxLength()).approximateTrimming();
        }
        return params;
    }

    private void expireIfConfigured(String key, int ttlSeconds) {
        if (ttlSeconds > 0) {
            jedis.expire(key, ttlSeconds);
        }
    }

    private <T, ID> void enforceHardLimits(WriteOperation<T, ID> operation) {
        if (!guardrailConfig.enabled() || !guardrailConfig.rejectWritesOnHardLimit()) {
            return;
        }
        String statsKey = keyStrategy.compactionStatsKey();
        Map<String, String> stats = jedis.hgetAll(statsKey);
        long pendingCount = parseLong(stats.get("pendingCount"));
        long payloadCount = parseLong(stats.get("payloadCount"));
        long backlog = backlogLength();
        String payloadKey = keyStrategy.compactionPayloadKey(operation.metadata().redisNamespace(), operation.id());
        String pendingKey = keyStrategy.compactionPendingKey(operation.metadata().redisNamespace(), operation.id());
        boolean newPayload = !jedis.exists(payloadKey);
        boolean newPending = !jedis.exists(pendingKey);

        if (guardrailConfig.writeBehindBacklogHardLimit() > 0
                && backlog >= guardrailConfig.writeBehindBacklogHardLimit()) {
            reject(statsKey, "write-behind backlog hard limit exceeded: " + backlog);
        }
        if (guardrailConfig.compactionPayloadHardLimit() > 0
                && newPayload
                && payloadCount >= guardrailConfig.compactionPayloadHardLimit()) {
            reject(statsKey, "compaction payload hard limit exceeded: " + payloadCount);
        }
        if (guardrailConfig.compactionPendingHardLimit() > 0
                && newPending
                && pendingCount >= guardrailConfig.compactionPendingHardLimit()) {
            reject(statsKey, "compaction pending hard limit exceeded: " + pendingCount);
        }
    }

    private void reject(String statsKey, String message) {
        jedis.hincrBy(statsKey, "hardRejectedWriteCount", 1L);
        throw new IllegalStateException(message);
    }

    private long backlogLength() {
        return RedisBacklogEstimator.estimateWriteBehindBacklog(jedis, keyStrategy, config);
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
