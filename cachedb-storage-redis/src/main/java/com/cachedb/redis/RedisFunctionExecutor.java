package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.model.OptimisticWriteConflictException;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.ArrayList;
import java.util.List;

public final class RedisFunctionExecutor {

    private final JedisPooled jedis;
    private final RedisFunctionsConfig config;
    private final RedisGuardrailConfig guardrailConfig;
    private final WriteBehindConfig writeBehindConfig;
    private final RedisFunctionArgsMapper argsMapper;

    public RedisFunctionExecutor(
            JedisPooled jedis,
            RedisFunctionsConfig config,
            RedisGuardrailConfig guardrailConfig,
            RedisFunctionArgsMapper argsMapper
    ) {
        this(jedis, config, guardrailConfig, WriteBehindConfig.defaults(), argsMapper);
    }

    public RedisFunctionExecutor(
            JedisPooled jedis,
            RedisFunctionsConfig config,
            RedisGuardrailConfig guardrailConfig,
            WriteBehindConfig writeBehindConfig,
            RedisFunctionArgsMapper argsMapper
    ) {
        this.jedis = jedis;
        this.config = config;
        this.guardrailConfig = guardrailConfig;
        this.writeBehindConfig = writeBehindConfig;
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
            CachePolicy cachePolicy,
            boolean cacheEntity
    ) {
        return upsert(
                entityKey, versionKey, tombstoneKey, streamKey, compactionPayloadKey,
                compactionPendingKey, compactionStreamKey, compactionStatsKey,
                operation, cachePolicy, cacheEntity, -1L
        );
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
            CachePolicy cachePolicy,
            boolean cacheEntity,
            long expectedVersion
    ) {
        Object result = jedis.fcall(
                config.upsertFunctionName(),
                List.of(entityKey, versionKey, tombstoneKey, streamKey, compactionPayloadKey, compactionPendingKey, compactionStreamKey, compactionStatsKey),
                argsMapper.upsertArgs(
                        operation, cachePolicy, guardrailConfig, writeBehindConfig, cacheEntity, expectedVersion
                )
        );
        long version = toLong(result);
        if (version < 0L) {
            throw new OptimisticWriteConflictException(
                    operation.metadata().entityName(),
                    operation.id(),
                    expectedVersion,
                    -(version + 1L)
            );
        }
        return version;
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
                argsMapper.deleteArgs(operation, guardrailConfig, writeBehindConfig)
        );
        return toLong(result);
    }

    public List<Long> upsertBatch(List<UpsertRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        ArrayList<Response<Object>> responses = new ArrayList<>(requests.size());
        try (Pipeline pipeline = jedis.pipelined()) {
            for (UpsertRequest request : requests) {
                responses.add(pipeline.fcall(
                        config.upsertFunctionName(),
                        request.keys(),
                        argsMapper.upsertArgs(
                                request.operation(),
                                request.cachePolicy(),
                                guardrailConfig,
                                writeBehindConfig,
                                request.cacheEntity(),
                                -1L
                        )
                ));
            }
            pipeline.sync();
        }
        ArrayList<Long> versions = new ArrayList<>(responses.size());
        for (Response<Object> response : responses) {
            long version = toLong(response.get());
            if (version <= 0) {
                throw new IllegalStateException("Bulk upsert returned a non-positive Redis version: " + version);
            }
            versions.add(version);
        }
        return List.copyOf(versions);
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

    public record UpsertRequest(
            List<String> keys,
            WriteOperation<?, ?> operation,
            CachePolicy cachePolicy,
            boolean cacheEntity
    ) {
        public UpsertRequest {
            keys = List.copyOf(keys);
        }
    }
}
