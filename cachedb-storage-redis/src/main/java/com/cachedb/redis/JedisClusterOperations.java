package com.reactor.cachedb.redis;

import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class JedisClusterOperations implements RedisClientOperations {
    private final JedisCluster client;

    public JedisClusterOperations(JedisCluster client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public JedisCluster commands() {
        return client;
    }

    public boolean clustered() {
        return true;
    }

    @Override public Optional<List<String>> zrangeWithPayloads(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        return fetchOrderedPayloads(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit);
    }

    @Override public Optional<List<OrderedPayload>> orderedPayloadWindow(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        return fetchOrderedPayloadWindow(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit);
    }

    @Override public Object memoryInfo(String routingKey) {
        String selected = null;
        double worst = -1;
        long largestUsed = -1;
        for (var pool : client.getClusterNodes().values()) {
            try (var jedis = new Jedis(pool.getResource())) {
                if (!jedis.info("replication").contains("role:master")) continue;
                String info = jedis.info("memory");
                long used = 0, max = 0;
                for (String line : info.split("\\r?\\n")) {
                    if (line.startsWith("used_memory:")) used = Long.parseLong(line.substring(12));
                    if (line.startsWith("maxmemory:")) max = Long.parseLong(line.substring(10));
                }
                double pressure = max > 0 ? (double) used / max : 0;
                if (pressure > worst || (pressure == worst && used > largestUsed)) {
                    worst = pressure; largestUsed = used; selected = info;
                }
            }
        }
        if (selected == null) throw new IllegalStateException("Redis Cluster has no reachable primary");
        return selected;
    }

    @Override
    public Object serverInfo(String section, String routingKey) {
        try (var jedis =
                new Jedis(
                        client.getConnectionFromSlot(
                                redis.clients.jedis.util.JedisClusterCRC16.getSlot(routingKey)))) {
            return jedis.info(section);
        }
    }

    @Override
    public String ping(String routingKey) {
        try (var jedis =
                new Jedis(
                        client.getConnectionFromSlot(
                                redis.clients.jedis.util.JedisClusterCRC16.getSlot(routingKey)))) {
            return jedis.ping();
        }
    }

    @Override
    public long delete(String... keys) {
        Map<Integer, List<String>> groups = new HashMap<>();
        for (var key : keys)
            groups.computeIfAbsent(
                            redis.clients.jedis.util.JedisClusterCRC16.getSlot(key),
                            ignored -> new ArrayList<>())
                    .add(key);
        long removed = 0;
        for (var group : groups.values()) removed += client.del(group.toArray(String[]::new));
        return removed;
    }

    public List<String> mget(String... keys) {
        List<String> values = new ArrayList<>(Collections.nCopies(keys.length, null));
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++)
            groups.computeIfAbsent(
                            redis.clients.jedis.util.JedisClusterCRC16.getSlot(keys[i]),
                            s -> new ArrayList<>())
                    .add(i);
        for (var indices : groups.values()) {
            var group = client.mget(indices.stream().map(i -> keys[i]).toArray(String[]::new));
            for (int i = 0; i < indices.size(); i++) values.set(indices.get(i), group.get(i));
        }
        return values;
    }

    public ScanResult<String> scan(String cursor, ScanParams params) {
        var names = new ArrayList<>(client.getClusterNodes().keySet());
        Collections.sort(names);
        String node = null, redisCursor = "0";
        if (!cursor.equals("0")) {
            String[] decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                            .split("\n", -1);
            if (decoded.length != 2)
                throw new IllegalArgumentException("Invalid cluster scan cursor");
            node = decoded[0];
            redisCursor = decoded[1];
        }
        int start = node == null ? 0 : names.indexOf(node);
        if (start < 0) throw new IllegalStateException("Cluster topology changed; restart scan");
        for (int i = start; i < names.size(); i++) {
            try (var jedis = new Jedis(client.getClusterNodes().get(names.get(i)).getResource())) {
                if (!jedis.info("replication").contains("role:master")) continue;
                var result = jedis.scan(i == start ? redisCursor : "0", params);
                String next =
                        result.getCursor().equals("0")
                                ? (i + 1 == names.size() ? "0" : cursor(names.get(i + 1), "0"))
                                : cursor(names.get(i), result.getCursor());
                return new ScanResult<>(next, result.getResult());
            }
        }
        return new ScanResult<>("0", List.of());
    }

    private static String cursor(String node, String cursor) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((node + "\n" + cursor).getBytes(StandardCharsets.UTF_8));
    }

    public String functionLoad(String code) {
        return load(code, false);
    }

    public String functionLoadReplace(String code) {
        return load(code, true);
    }

    private String load(String code, boolean replace) {
        String name = null;
        for (var pool : client.getClusterNodes().values()) {
            try (var jedis = new Jedis(pool.getResource())) {
                if (!jedis.info("replication").contains("role:master")) continue;
                name = replace ? jedis.functionLoadReplace(code) : jedis.functionLoad(code);
            }
        }
        if (name == null) throw new IllegalStateException("Redis Cluster has no reachable primary");
        return name;
    }
}
