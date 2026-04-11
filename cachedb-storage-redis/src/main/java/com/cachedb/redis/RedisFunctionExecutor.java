package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.model.WriteOperation;
import redis.clients.jedis.JedisPooled;

import java.util.List;

public final class RedisFunctionExecutor {

    private final JedisPooled jedis;
    private final RedisFunctionsConfig config;
    private final RedisGuardrailConfig guardrailConfig;
    private final RedisFunctionArgsMapper argsMapper;

    public RedisFunctionExecutor(
            JedisPooled jedis,
            RedisFunctionsConfig config,
            RedisGuardrailConfig guardrailConfig,
            RedisFunctionArgsMapper argsMapper
    ) {
        this.jedis = jedis;
        this.config = config;
        this.guardrailConfig = guardrailConfig;
        this.argsMapper = argsMapper;
    }

    public boolean enabled() {
        return config.enabled();
    }

    public <T, ID> long upsert(
            String entityKey,
            String versionKey,
            String tombstoneKey,
            String streamKey,
            String compactionPayloadKey,
            String compactionPendingKey,
            String compactionStreamKey,
            String compactionStatsKey,
            WriteOperation<T, ID> operation,
            CachePolicy cachePolicy
    ) {
        Object result = jedis.fcall(
                config.upsertFunctionName(),
                List.of(entityKey, versionKey, tombstoneKey, streamKey, compactionPayloadKey, compactionPendingKey, compactionStreamKey, compactionStatsKey),
                argsMapper.upsertArgs(operation, cachePolicy, guardrailConfig)
        );
        return toLong(result);
    }

    public <T, ID> long delete(
            String entityKey,
            String versionKey,
            String tombstoneKey,
            String streamKey,
            String compactionPayloadKey,
            String compactionPendingKey,
            String compactionStreamKey,
            String compactionStatsKey,
            WriteOperation<T, ID> operation
    ) {
        Object result = jedis.fcall(
                config.deleteFunctionName(),
                List.of(entityKey, versionKey, tombstoneKey, streamKey, compactionPayloadKey, compactionPendingKey, compactionStreamKey, compactionStatsKey),
                argsMapper.deleteArgs(operation, guardrailConfig)
        );
        return toLong(result);
    }

    public String compactionComplete(
            String compactionPendingKey,
            String compactionPayloadKey,
            String compactionStreamKey,
            String compactionStatsKey,
            String namespace,
            String id,
            long flushedVersion
    ) {
        Object result = jedis.fcall(
                config.compactionCompleteFunctionName(),
                List.of(compactionPendingKey, compactionPayloadKey, compactionStreamKey, compactionStatsKey),
                List.of(namespace, id, String.valueOf(flushedVersion))
        );
        return String.valueOf(result);
    }

    private long toLong(Object result) {
        if (result instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(result));
    }
}
