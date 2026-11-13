package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.util.JedisClusterCRC16;

@EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
@Isolated("Cluster point-read script cache and ACL fixtures")
@Timeout(60)
class RedisBoundedPointReadClusterTest {
    @Test
    @EnabledIfSystemProperty(named = "cachedb.redis.script.flush", matches = "true")
    void coldWarmAndFlushedOwnersKeepSplitSlotValuesTtlAndRequestOrder() {
        // SCRIPT FLUSH is node-local and requires an explicitly opted-in, dedicated cluster.
        try (var fixture = new Fixture()) {
            var client = new RedisBoundedPointReadTest.RecordingClient(fixture.operations);
            fixture.eachNode(jedis -> { if (master(jedis)) jedis.scriptFlush(); });
            fixture.assertRead(client);
            int coldLoads = client.evalCalls;
            assertEquals(4, client.shaCalls);
            assertTrue(coldLoads >= 1 && coldLoads <= 4);
            fixture.assertRead(client);
            assertEquals(8, client.shaCalls);
            assertEquals(coldLoads, client.evalCalls);
            fixture.eachNode(jedis -> { if (master(jedis)) jedis.scriptFlush(); });
            fixture.assertRead(client);
            assertEquals(12, client.shaCalls);
            assertEquals(2 * coldLoads, client.evalCalls);
        }
    }

    @Test void evalshaRejectsRawCrossSlotKeysButBoundedReadGroupsThem() {
        try (var fixture = new Fixture()) {
            fixture.eachNode(jedis -> { if (master(jedis)) jedis.scriptLoad(RedisBoundedPointRead.SCRIPT); });
            var client = new RedisBoundedPointReadTest.RecordingClient(fixture.operations);
            fixture.assertRead(client);
            assertEquals(4, client.shaCalls);
            assertEquals(0, client.evalCalls);
            var failure = assertThrows(JedisException.class, () -> fixture.operations.commands().evalsha(client.sha,
                    fixture.keys.subList(0, 2).stream().map(key -> key.getBytes(StandardCharsets.UTF_8)).toList(),
                    List.of("1024".getBytes(StandardCharsets.US_ASCII), "0".getBytes(StandardCharsets.US_ASCII),
                            "0".getBytes(StandardCharsets.US_ASCII))));
            assertTrue(failure.getMessage().contains("CROSSSLOT")
                    || failure.getMessage().equals("Keys must belong to same hashslot."), failure.getMessage());
        }
    }

    @Test void evalOnlyClusterUserFallsBackPerSlotAndStillPreservesOrder() {
        try (var fixture = new Fixture()) {
            String user = "bounded_sha_" + UUID.randomUUID().toString().replace("-", "");
            String password = UUID.randomUUID().toString();
            try {
                fixture.eachNode(jedis -> jedis.sendCommand(Command.ACL, "SETUSER", user, "reset", "on",
                        ">" + password, "~" + fixture.prefix + "*", "+eval", "+strlen", "+mget", "+pttl",
                        "+ping", "+hello", "+client|setinfo", "+cluster|slots", "+cluster|shards", "-evalsha"));
                var config = DefaultJedisClientConfig.builder().user(user).password(password)
                        .connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                        .hostAndPortMapper(address -> System.getProperty("cachedb.redis.cluster.host") != null
                                ? address : new HostAndPort("127.0.0.1",
                                        address.getPort() < 10000 ? address.getPort() + 10000 : address.getPort()))
                        .build();
                try (var authenticated = new JedisClusterOperations(new JedisCluster(Set.of(new HostAndPort(
                        System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"),
                        Integer.getInteger("cachedb.redis.cluster.port", 17001))), config))) {
                    var client = new RedisBoundedPointReadTest.RecordingClient(authenticated);
                    fixture.assertRead(client);
                    assertEquals(4, client.shaCalls);
                    assertEquals(4, client.evalCalls);
                }
            } finally { fixture.eachNode(jedis -> jedis.sendCommand(Command.ACL, "DELUSER", user)); }
        }
    }

    private static boolean master(Jedis jedis) { return jedis.info("replication").contains("role:master"); }

    private static final class Fixture implements AutoCloseable {
        final JedisClusterOperations operations = RedisReadCoalescingClusterTest.connect();
        final String prefix = "bounded-sha-cluster-" + UUID.randomUUID();
        final List<String> keys = List.of(prefix + ":{a}:e", prefix + ":{b}:t",
                prefix + ":{c}:e", prefix + ":{d}:t");
        final List<RedisBoundedPointRead.KeyPair> pairs = List.of(
                new RedisBoundedPointRead.KeyPair(keys.get(0), keys.get(1), true),
                new RedisBoundedPointRead.KeyPair(keys.get(2), keys.get(3), true),
                new RedisBoundedPointRead.KeyPair(keys.get(0), keys.get(3), true));
        Fixture() {
            assertEquals(4, keys.stream().map(JedisClusterCRC16::getSlot).distinct().count());
            operations.commands().setex(keys.get(0), 120, "alpha");
            operations.commands().setex(keys.get(1), 120, "1");
            operations.commands().setex(keys.get(2), 120, "charlie");
            operations.commands().setex(keys.get(3), 120, "1");
        }
        void assertRead(RedisClientOperations client) {
            var result = RedisBoundedPointRead.readMeasured(client, pairs, 20);
            assertEquals(20, result.contentBytes());
            assertEquals(List.of("alpha", "charlie", "alpha"),
                    java.util.Arrays.stream(result.reads()).map(RedisPointRead::payload).toList());
            for (var read : result.reads()) {
                assertEquals("1", read.tombstone());
                assertTrue(read.ttlMillis() > 0);
            }
        }
        void eachNode(Consumer<Jedis> action) {
            assertAll(operations.commands().getClusterNodes().values().stream().map(pool -> () -> {
                try (var jedis = new Jedis(pool.getResource())) { action.accept(jedis); }
            }));
        }
        @Override public void close() {
            try { operations.delete(keys.toArray(String[]::new)); }
            finally { operations.close(); }
        }
    }
}
