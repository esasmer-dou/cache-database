package com.reactor.cachedb.redis;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.ArrayList;
import java.util.List;

final class RedisVersionedHydrator {

    private static final String UPSERT_SCRIPT = """
            local incoming = tonumber(ARGV[1])
            if not incoming or incoming <= 0 then
                return redis.error_reply('incoming version must be greater than zero')
            end
            local currentVersion = tonumber(redis.call('GET', KEYS[2]) or '0')
            local tombstoneVersion = tonumber(redis.call('GET', KEYS[3]) or '0')
            if tombstoneVersion >= incoming then
                return 0
            end
            if currentVersion > incoming then
                return 0
            end
            if tonumber(ARGV[3]) > 0 then
                redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            else
                redis.call('SET', KEYS[1], ARGV[2])
            end
            redis.call('SET', KEYS[2], ARGV[1])
            redis.call('DEL', KEYS[3])
            return 1
            """;

    private static final String DELETE_SCRIPT = """
            local incoming = tonumber(ARGV[1])
            if not incoming or incoming <= 0 then
                return redis.error_reply('incoming version must be greater than zero')
            end
            local currentVersion = tonumber(redis.call('GET', KEYS[2]) or '0')
            local tombstoneVersion = tonumber(redis.call('GET', KEYS[3]) or '0')
            if math.max(currentVersion, tombstoneVersion) >= incoming then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('SET', KEYS[2], ARGV[1])
            if tonumber(ARGV[2]) > 0 then
                redis.call('SET', KEYS[3], ARGV[1], 'EX', ARGV[2])
            else
                redis.call('SET', KEYS[3], ARGV[1])
            end
            return 1
            """;

    private final JedisPooled jedis;

    RedisVersionedHydrator(JedisPooled jedis) {
        this.jedis = jedis;
    }

    boolean upsert(String entityKey, String versionKey, String tombstoneKey, String payload, long version, long ttlSeconds) {
        Object result = jedis.eval(
                UPSERT_SCRIPT,
                List.of(entityKey, versionKey, tombstoneKey),
                List.of(String.valueOf(version), payload, String.valueOf(Math.max(0, ttlSeconds)))
        );
        return accepted(result);
    }

    List<Boolean> upsertBatch(List<Upsert> operations) {
        if (operations.isEmpty()) {
            return List.of();
        }
        ArrayList<Response<Object>> responses = new ArrayList<>(operations.size());
        try (Pipeline pipeline = jedis.pipelined()) {
            for (Upsert operation : operations) {
                responses.add(pipeline.eval(
                        UPSERT_SCRIPT,
                        List.of(operation.entityKey(), operation.versionKey(), operation.tombstoneKey()),
                        List.of(
                                String.valueOf(operation.version()),
                                operation.payload(),
                                String.valueOf(Math.max(0, operation.ttlSeconds()))
                        )
                ));
            }
            pipeline.sync();
        }
        return responses.stream().map(Response::get).map(RedisVersionedHydrator::accepted).toList();
    }

    boolean delete(String entityKey, String versionKey, String tombstoneKey, long version, int tombstoneTtlSeconds) {
        Object result = jedis.eval(
                DELETE_SCRIPT,
                List.of(entityKey, versionKey, tombstoneKey),
                List.of(String.valueOf(version), String.valueOf(Math.max(0, tombstoneTtlSeconds)))
        );
        return accepted(result);
    }

    private static boolean accepted(Object result) {
        return result instanceof Number number && number.longValue() == 1L;
    }

    record Upsert(
            String entityKey,
            String versionKey,
            String tombstoneKey,
            String payload,
            long version,
            long ttlSeconds
    ) {
    }
}
