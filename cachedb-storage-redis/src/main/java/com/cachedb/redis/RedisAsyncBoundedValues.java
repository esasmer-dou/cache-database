package com.reactor.cachedb.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import redis.clients.jedis.util.JedisClusterCRC16;

/** Bounded metadata reads through the ordinary cluster executor; no cross-slot pipeline fallback. */
final class RedisAsyncBoundedValues {
    private record Key(int index, byte[] name) {}
    private static final byte[] SCRIPT = RedisBoundedPointRead.SCRIPT.getBytes(StandardCharsets.UTF_8);

    static List<String> read(RedisClientOperations client, List<String> keys, long maximum) {
        if (keys.isEmpty()) return List.of();
        if (keys.size() > 512 || maximum < 1 || maximum > RedisAsyncReadBudget.CONTENT_BYTES)
            throw new IllegalArgumentException("Invalid native metadata read bound");
        var groups = new LinkedHashMap<Integer, ArrayList<Key>>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            groups.computeIfAbsent(client.clustered() ? JedisClusterCRC16.getSlot(key) : 0,
                    ignored -> new ArrayList<>()).add(new Key(i, key.getBytes(StandardCharsets.UTF_8)));
        }
        var result = new String[keys.size()];
        long used = 0;
        for (var group : groups.values()) {
            var names = new ArrayList<byte[]>(group.size());
            var args = new ArrayList<byte[]>(group.size() + 1);
            args.add(Long.toString(maximum - used).getBytes(StandardCharsets.US_ASCII));
            for (var key : group) { names.add(key.name()); args.add(new byte[] {'0'}); }
            Object raw;
            if (client.clustered()) raw = client.commands().eval(SCRIPT, names, args);
            else try (var pipeline = client.pipeline()) {
                var response = pipeline.eval(SCRIPT, names, args);
                pipeline.sync();
                raw = response.get();
            }
            if (!(raw instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof List<?> values)
                    || !(pair.get(1) instanceof List<?> ttls) || values.size() != group.size() || ttls.size() != group.size())
                throw new IllegalStateException("Malformed native metadata reply");
            for (int i = 0; i < group.size(); i++) {
                Object value = values.get(i);
                if (value != null && !(value instanceof byte[]) || !Long.valueOf(-3).equals(ttls.get(i)))
                    throw new IllegalStateException("Malformed native metadata position");
                byte[] bytes = (byte[]) value;
                used += bytes == null ? 0 : bytes.length;
                if (used > maximum) throw new IllegalStateException("Oversized native metadata reply");
                result[group.get(i).index()] = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
            }
        }
        return Arrays.asList(result);
    }

    private RedisAsyncBoundedValues() {}
}
