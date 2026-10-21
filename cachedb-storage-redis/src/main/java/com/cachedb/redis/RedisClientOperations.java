package com.reactor.cachedb.redis;

import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.*;

/** Client lifecycle and topology-dependent operations; keyed commands use Jedis' shared API. */
public interface RedisClientOperations extends AutoCloseable {
    UnifiedJedis commands();

    default boolean clustered() {
        return false;
    }

    default Object serverInfo(String section, String routingKey) {
        return commands().sendCommand(redis.clients.jedis.Protocol.Command.INFO, section);
    }

    default Object memoryInfo(String routingKey) { return serverInfo("memory", routingKey); }

    default String ping(String routingKey) {
        return commands().ping();
    }

    default long delete(String... keys) {
        return commands().del(keys);
    }

    default AbstractPipeline pipeline() {
        return commands().pipelined();
    }

    default ScanResult<String> scan(String cursor, ScanParams params) {
        return commands().scan(cursor, params);
    }

    default List<String> mget(String... keys) {
        return commands().mget(keys);
    }

    default Object eval(String script, List<String> keys, List<String> arguments) {
        requireSameSlot(keys);
        return commands().eval(script, keys, arguments);
    }

    default Object eval(String script, int keyCount, String... arguments) {
        requireSameSlot(Arrays.asList(arguments).subList(0, keyCount));
        return commands().eval(script, keyCount, arguments);
    }

    default Object fcall(String function, List<String> keys, List<String> arguments) {
        requireSameSlot(keys);
        return commands().fcall(function, keys, arguments);
    }

    default void requireSameSlot(Collection<String> keys) {
        if (!clustered() || keys.isEmpty()) return;
        int slot = -1;
        for (String key : keys) {
            int current = redis.clients.jedis.util.JedisClusterCRC16.getSlot(key);
            if (slot >= 0 && slot != current)
                throw new IllegalArgumentException(
                        "Atomic Redis keys must share one Cluster hash slot");
            slot = current;
        }
    }

    default String functionLoad(String code) {
        return commands().functionLoad(code);
    }

    default String functionLoadReplace(String code) {
        return commands().functionLoadReplace(code);
    }

    default JedisPooled pooledClient() {
        throw new UnsupportedOperationException("Redis client uses Cluster topology");
    }

    @Override
    default void close() {
        commands().close();
    }
}
