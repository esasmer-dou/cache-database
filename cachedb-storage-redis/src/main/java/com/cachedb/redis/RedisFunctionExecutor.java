package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.OptimisticWriteConflictException;
import com.reactor.cachedb.core.model.WriteOperation;

import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Response;

import java.util.ArrayList;
import java.util.List;

public final class RedisFunctionExecutor {

    private final RedisClientOperations jedis;
    private final RedisFunctionsConfig config;
    private final RedisGuardrailConfig guardrailConfig;
    private final WriteBehindConfig writeBehindConfig;
    private final RedisFunctionArgsMapper argsMapper;
    private final java.util.concurrent.locks.ReentrantLock reloadLock =
            new java.util.concurrent.locks.ReentrantLock();
    private volatile long reloadEpoch;
    private volatile RedisKeyStrategy protectedRuntimeKeys;

    /** Bind the actual runtime strategy once; equal prefixes are not interchangeable capabilities. */
    public synchronized RedisFunctionExecutor withProtectedRuntime(RedisKeyStrategy keys) {
        java.util.Objects.requireNonNull(keys);
        if (protectedRuntimeKeys != null && protectedRuntimeKeys != keys)
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL);
        protectedRuntimeKeys = keys;
        return this;
    }

    private boolean missingFunction(redis.clients.jedis.exceptions.JedisDataException failure) {
        String message = failure.getMessage();
        return message != null
                && (message.toLowerCase(java.util.Locale.ROOT).contains("function not found")
                        || message.startsWith("NOSCRIPT"));
    }

    private void reload(long epoch) {
        reloadLock.lock();
        try {
            if (epoch == reloadEpoch) {
                new RedisFunctionLoader(jedis, config, new RedisFunctionLibrarySource())
                        .reloadMissingLibrary();
                reloadEpoch++;
            }
        } finally {
            reloadLock.unlock();
        }
    }

    private Object send(String name, List<String> keys, List<String> args) {
        try { return jedis.fcall(name, keys, args); }
        catch (RuntimeException failure) { RedisTenantProducer.transportFailure(failure); throw failure; }
    }

    private Object call(String name, List<String> keys, List<String> args) {
        long epoch = reloadEpoch;
        try {
            return send(name, keys, args);
        } catch (redis.clients.jedis.exceptions.JedisDataException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("CacheDB tenant access denied"))
                throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException("atomic write");
            if (!missingFunction(failure) || !config.autoLoadLibrary()) throw failure;
            reload(epoch);
            return send(name, keys, args);
        }
    }

    public RedisFunctionExecutor(
            JedisPooled jedis,
            RedisFunctionsConfig config,
            RedisGuardrailConfig guardrailConfig,
            RedisFunctionArgsMapper argsMapper) {
        this(JedisPooledOperations.of(jedis), config, guardrailConfig, argsMapper);
    }

    public RedisFunctionExecutor(
            RedisClientOperations jedis,
            RedisFunctionsConfig config,
            RedisGuardrailConfig guardrailConfig,
            RedisFunctionArgsMapper argsMapper) {
        this(jedis, config, guardrailConfig, WriteBehindConfig.defaults(), argsMapper);
    }

    public RedisFunctionExecutor(
            JedisPooled jedis,
            RedisFunctionsConfig config,
            RedisGuardrailConfig guardrailConfig,
            WriteBehindConfig writeBehindConfig,
            RedisFunctionArgsMapper argsMapper) {
        this(
                JedisPooledOperations.of(jedis),
                config,
                guardrailConfig,
                writeBehindConfig,
                argsMapper);
    }

    public RedisFunctionExecutor(
            RedisClientOperations jedis,
            RedisFunctionsConfig config,
            RedisGuardrailConfig guardrailConfig,
            WriteBehindConfig writeBehindConfig,
            RedisFunctionArgsMapper argsMapper) {
        this.jedis = jedis;
        this.config = config;
        this.guardrailConfig = guardrailConfig;
        this.writeBehindConfig = writeBehindConfig;
        this.argsMapper = argsMapper;
    }

    public boolean enabled() {
        return config.enabled();
    }

    private RedisProtectedFunctionCall.Frame prepareOperation(List<String> keys, List<String> args,
            WriteOperation<?, ?> operation, boolean delete) {
        if (operation.metadata().tenantPurgeEnabled())
            return RedisProtectedFunctionCall.prepare(jedis, config, protectedRuntimeKeys, writeBehindConfig, keys, args, operation, delete);
        return new RedisProtectedFunctionCall.Frame(tenantKeys(keys, operation), args);
    }

    private Object callOperation(String name, List<String> keys, List<String> args,
            WriteOperation<?, ?> operation, boolean delete) {
        var request = prepareOperation(keys, args, operation, delete);
        return call(name, request.keys(), request.args());
    }

    private List<String> tenantKeys(List<String> keys, WriteOperation<?, ?> operation) {
        com.reactor.cachedb.core.protection.TenantProtectionGate.requireLegacy(operation.metadata());
        if (RedisTenantWriteGuard.tenant(operation) == null) return keys;
        var result = new ArrayList<>(keys);
        result.add(keys.get(1) + ":tenant");
        return result;
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
            boolean cacheEntity) {
        return upsert(
                entityKey,
                versionKey,
                tombstoneKey,
                streamKey,
                compactionPayloadKey,
                compactionPendingKey,
                compactionStreamKey,
                compactionStatsKey,
                operation,
                cachePolicy,
                cacheEntity,
                -1L);
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
            long expectedVersion) {
        Object result =
                callOperation(
                        config.upsertFunctionName(),
                        List.of(
                                entityKey,
                                versionKey,
                                tombstoneKey,
                                streamKey,
                                compactionPayloadKey,
                                compactionPendingKey,
                                compactionStreamKey,
                                compactionStatsKey),
                        argsMapper.upsertArgs(
                                operation,
                                cachePolicy,
                                guardrailConfig,
                                writeBehindConfig,
                                cacheEntity,
                                expectedVersion), operation, false);
        long version = toLong(result);
        if (version < 0L) {
            throw new OptimisticWriteConflictException(
                    operation.metadata().entityName(),
                    operation.id(),
                    expectedVersion,
                    -(version + 1L));
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
            WriteOperation<T, ID> operation) {
        return delete(entityKey, versionKey, tombstoneKey, streamKey, compactionPayloadKey,
                compactionPendingKey, compactionStreamKey, compactionStatsKey, operation, CachePolicy.defaults());
    }

    public <T, ID> long delete(String entityKey, String versionKey, String tombstoneKey, String streamKey,
            String compactionPayloadKey, String compactionPendingKey, String compactionStreamKey,
            String compactionStatsKey, WriteOperation<T, ID> operation, CachePolicy policy) {
        return delete(entityKey, versionKey, tombstoneKey, streamKey, compactionPayloadKey,
                compactionPendingKey, compactionStreamKey, compactionStatsKey, operation, policy, -1);
    }

    public <T, ID> long delete(String entityKey, String versionKey, String tombstoneKey, String streamKey,
            String compactionPayloadKey, String compactionPendingKey, String compactionStreamKey,
            String compactionStatsKey, WriteOperation<T, ID> operation, CachePolicy policy, long expectedVersion) {
        var args = new ArrayList<>(argsMapper.deleteArgs(operation, guardrailConfig, writeBehindConfig, policy));
        if (expectedVersion >= 0) {
            while (args.size() < 20) args.add("");
            args.add(Long.toString(expectedVersion));
        }
        Object result =
                callOperation(
                        config.deleteFunctionName(),
                        List.of(
                                entityKey,
                                versionKey,
                                tombstoneKey,
                                streamKey,
                                compactionPayloadKey,
                                compactionPendingKey,
                                compactionStreamKey,
                                compactionStatsKey),
                        args, operation, true);
        long version = toLong(result);
        if (version < 0) throw new OptimisticWriteConflictException(operation.metadata().entityName(), operation.id(), expectedVersion, -(version + 1));
        return version;
    }

    record DeleteRequest(List<String> keys, WriteOperation<?, ?> operation, CachePolicy policy,
            long expectedVersion, String hotOwnerKey, String hotOwner, List<List<String>> maintenance) {
        DeleteRequest {
            keys = List.copyOf(keys);
            maintenance = List.copyOf(maintenance);
        }
    }

    record PreparedDeleteBatch(List<String> keys, List<String> args, int size) {}
    boolean supportsBulkDelete() {
        return enabled() && "/functions/cachedb-functions.lua".equals(config.templateResourcePath())
                && (config.sourceOverride() == null || config.sourceOverride().isBlank());
    }

    /** Pure framing/validation, completed before the caller enters the uncertain network boundary. */
    PreparedDeleteBatch prepareDeleteBatch(List<DeleteRequest> requests) {
        if (requests.isEmpty() || requests.size() > 128) throw new IllegalArgumentException("Delete chunks contain 1..128 rows");
        var keys = new ArrayList<String>(); var args = new ArrayList<String>();
        args.add(Integer.toString(requests.size()));
        for (var request : requests) {
            RedisEncryptedBulkDeleteChecks.requireIdentityOnly(request.operation(), request.expectedVersion());
            var rawArgs = new ArrayList<>(argsMapper.deleteArgs(request.operation(), guardrailConfig, writeBehindConfig, request.policy()));
            while (rawArgs.size() < 20) rawArgs.add("");
            rawArgs.add(Long.toString(request.expectedVersion()));
            var row = prepareOperation(request.keys(), rawArgs, request.operation(), true);
            var routed = row.keys(); var rowArgs = row.args();
            args.add(Integer.toString(routed.size())); args.add(Integer.toString(rowArgs.size()));
            args.add(Integer.toString(request.maintenance().size()));
            keys.addAll(routed); args.addAll(rowArgs);
            keys.add(request.hotOwnerKey()); args.add(request.hotOwner() == null ? "" : request.hotOwner());
            for (var command : request.maintenance()) {
                keys.add(command.get(1)); args.add(command.get(0)); args.add(Integer.toString(command.size()));
                args.addAll(command.subList(2, command.size()));
            }
        }
        if (requests.stream().anyMatch(request -> request.operation().metadata().tenantPurgeEnabled())) {
            long bytes = 0;
            for (String key : keys) bytes += com.reactor.cachedb.core.codec.Utf8Length.of(key);
            for (String arg : args) bytes += com.reactor.cachedb.core.codec.Utf8Length.of(arg);
            if (bytes > 8L * 1024 * 1024) throw new IllegalArgumentException("Protected delete chunk exceeds 8 MiB");
        }
        RedisBulkDeleteExecutor.validateWire(keys, args);
        jedis.requireSameSlot(keys);
        return new PreparedDeleteBatch(List.copyOf(keys), List.copyOf(args), requests.size());
    }

    /** Same delete body/mapper as a single write; per-row outcomes, never whole-call atomicity. */
    List<List<String>> deleteBatch(PreparedDeleteBatch prepared) {
        Object raw = call(config.deleteFunctionName() + "_bulk", prepared.keys(), prepared.args());
        if (!(raw instanceof List<?> rows) || rows.size() != prepared.size())
            throw new IllegalStateException("Malformed bounded delete result");
        var result = new ArrayList<List<String>>(rows.size());
        for (Object row : rows) {
            if (!(row instanceof List<?> values) || values.size() != 3)
                throw new IllegalStateException("Malformed bounded delete outcome");
            result.add(values.stream().map(v -> v instanceof byte[] b
                    ? new String(b, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(v)).toList());
        }
        return List.copyOf(result);
    }

    public List<Long> upsertBatch(List<UpsertRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        if (requests.size() > 128) throw new IllegalArgumentException("Upsert chunks contain at most 128 rows");
        var prepared = new ArrayList<RedisProtectedFunctionCall.Frame>(requests.size());
        boolean protectedBatch = requests.stream().anyMatch(request -> request.operation().metadata().tenantPurgeEnabled());
        long preparedBytes = 0;
        for (UpsertRequest request : requests) {
            var framed = prepareOperation(request.keys(), argsMapper.upsertArgs(request.operation(), request.cachePolicy(),
                    guardrailConfig, writeBehindConfig, request.cacheEntity(), -1L), request.operation(), false);
            if (protectedBatch) {
                for (String key : framed.keys()) preparedBytes += com.reactor.cachedb.core.codec.Utf8Length.of(key);
                for (String arg : framed.args()) preparedBytes += com.reactor.cachedb.core.codec.Utf8Length.of(arg);
                if (preparedBytes > 8L * 1024 * 1024)
                    throw new IllegalArgumentException("Protected write chunk exceeds 8 MiB");
            }
            prepared.add(framed);
        }
        ArrayList<Response<Object>> responses = new ArrayList<>(requests.size());
        try (AbstractPipeline pipeline = jedis.pipeline()) {
            for (var request : prepared)
                responses.add(pipeline.fcall(config.upsertFunctionName(), request.keys(), request.args()));
            pipeline.sync();
        }
        ArrayList<Long> versions = new ArrayList<>(responses.size());
        for (int i = 0; i < responses.size(); i++) {
            Object result;
            try {
                result = responses.get(i).get();
            } catch (redis.clients.jedis.exceptions.JedisDataException failure) {
                if (failure.getMessage() != null && failure.getMessage().contains("CacheDB tenant access denied"))
                    throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException("atomic batch write");
                if (!missingFunction(failure) || !config.autoLoadLibrary()) throw failure;
                var request = prepared.get(i);
                result = call(config.upsertFunctionName(), request.keys(), request.args());
            }
            long version = toLong(result);
            if (version <= 0) {
                throw new IllegalStateException(
                        "Bulk upsert returned a non-positive Redis version: " + version);
            }
            versions.add(version);
        }
        return List.copyOf(versions);
    }

    public <T, ID> long patch(List<String> keys, WriteOperation<T, ID> operation,
            CachePolicy policy, boolean cacheEntity, long expectedVersion) {
        if (keys.size() != 9 || operation.type() != com.reactor.cachedb.core.model.OperationType.PATCH)
            throw new IllegalArgumentException("Invalid PATCH function request");
        // Deltas cannot replace the compaction payload, but must reach its consumers.
        List<String> routedKeys = keys;
        if (writeBehindConfig.compactionActive()) {
            routedKeys = new ArrayList<>(keys);
            routedKeys.set(3, keys.get(6));
        }
        long version = toLong(callOperation(config.upsertFunctionName(), routedKeys,
                argsMapper.upsertArgs(operation, policy, guardrailConfig, writeBehindConfig, cacheEntity, expectedVersion),
                operation, false));
        if (version < 0) throw new OptimisticWriteConflictException(operation.metadata().entityName(), operation.id(),
                expectedVersion, -(version + 1));
        return version;
    }

    public String compactionComplete(
            String compactionPendingKey,
            String compactionPayloadKey,
            String compactionStreamKey,
            String compactionStatsKey,
            String namespace,
            String id,
            long flushedVersion) {
        Object result =
                call(
                        config.compactionCompleteFunctionName(),
                        List.of(
                                compactionPendingKey,
                                compactionPayloadKey,
                                compactionStreamKey,
                                compactionStatsKey),
                        List.of(namespace, id, String.valueOf(flushedVersion)));
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
            boolean cacheEntity) {
        public UpsertRequest {
            keys = List.copyOf(keys);
        }
    }
}
