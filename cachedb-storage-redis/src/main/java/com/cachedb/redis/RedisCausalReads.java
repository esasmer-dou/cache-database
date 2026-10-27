package com.reactor.cachedb.redis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import redis.clients.jedis.util.JedisClusterCRC16;

/** Bounded per-slot reads. Split triples observe the version before any payload read. */
final class RedisCausalReads {
    private RedisCausalReads() {}
    private record Key(int position, String name) {}

    static List<String> read(RedisClientOperations redis, List<String> entities, List<String> tombstones,
            List<String> versions, BiConsumer<Integer, String> verifyVersion) {
        int size = entities.size();
        if (size > 512 || tombstones.size() != size || versions.size() != size)
            throw new IllegalArgumentException("Causal read requires matching batches of at most 512 ids");
        if (!redis.clustered()) {
            var keys = new ArrayList<String>(size * 3);
            keys.addAll(entities); keys.addAll(tombstones); keys.addAll(versions);
            return redis.mget(keys.toArray(String[]::new));
        }
        String[] values = new String[size * 3];
        Map<Integer, List<Key>> atomic = new LinkedHashMap<>(), versionFirst = new LinkedHashMap<>(), payloads = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            int slot = JedisClusterCRC16.getSlot(entities.get(i));
            if (slot == JedisClusterCRC16.getSlot(tombstones.get(i)) && slot == JedisClusterCRC16.getSlot(versions.get(i))) {
                add(atomic, new Key(i, entities.get(i)));
                add(atomic, new Key(size + i, tombstones.get(i)));
                add(atomic, new Key(2 * size + i, versions.get(i)));
            } else {
                add(versionFirst, new Key(2 * size + i, versions.get(i)));
                add(payloads, new Key(i, entities.get(i)));
                add(payloads, new Key(size + i, tombstones.get(i)));
            }
        }
        readGroups(redis, atomic, values);
        readGroups(redis, versionFirst, values);
        for (int i = 0; i < size; i++) verifyVersion.accept(i, values[2 * size + i]);
        readGroups(redis, payloads, values);
        return Arrays.asList(values);
    }

    private static void add(Map<Integer, List<Key>> groups, Key key) {
        groups.computeIfAbsent(JedisClusterCRC16.getSlot(key.name()), ignored -> new ArrayList<>()).add(key);
    }

    private static void readGroups(RedisClientOperations redis, Map<Integer, List<Key>> groups, String[] values) {
        for (List<Key> group : groups.values()) {
            var reply = redis.mget(group.stream().map(Key::name).toArray(String[]::new));
            if (reply.size() != group.size()) throw new IllegalStateException("Incomplete causal Redis reply");
            for (int i = 0; i < group.size(); i++) values[group.get(i).position()] = reply.get(i);
        }
    }
}
