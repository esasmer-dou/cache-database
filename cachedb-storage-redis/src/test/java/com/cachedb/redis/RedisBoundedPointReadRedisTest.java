package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Isolated("Point-read script cache and ACL fixtures")
@Timeout(30)
class RedisBoundedPointReadRedisTest {
    @Test
    @EnabledIfSystemProperty(named = "cachedb.redis.script.flush", matches = "true")
    void coldWarmAndFlushedCacheKeepBinaryValuesTtlAndOneFallback() {
        // SCRIPT FLUSH requires an explicitly opted-in, dedicated Redis fixture.
        try (var fixture = new Fixture()) {
            var client = new RedisBoundedPointReadTest.RecordingClient(fixture.operations);
            fixture.redis.scriptFlush();
            fixture.assertRead(client);
            assertEquals(1, client.shaCalls);
            assertEquals(1, client.evalCalls);
            fixture.assertRead(client);
            assertEquals(2, client.shaCalls);
            assertEquals(1, client.evalCalls);
            fixture.redis.scriptFlush();
            fixture.assertRead(client);
            assertEquals(3, client.shaCalls);
            assertEquals(2, client.evalCalls);
        }
    }

    @Test void evalOnlyUserKeepsWorkingWithoutCachingItsAclDecision() {
        try (var fixture = new Fixture()) {
            fixture.withUser("~" + fixture.prefix + "*", "-evalsha", client -> {
                fixture.assertRead(client);
                fixture.assertRead(client);
                assertEquals(2, client.shaCalls);
                assertEquals(2, client.evalCalls);
            });
        }
    }

    @Test void evalshaKeyDenialNeverFallsBackEvenWhenEvalIsAllowed() {
        try (var fixture = new Fixture()) {
            fixture.redis.scriptLoad(RedisBoundedPointRead.SCRIPT);
            fixture.withUser("~" + fixture.prefix + ":not-these-keys:*", "+evalsha", client -> {
                assertThrows(JedisAccessControlException.class, () -> fixture.read(client, 1024));
                assertEquals(1, client.shaCalls);
                assertEquals(0, client.evalCalls);
            });
        }
    }

    @Test void scriptInnerCommandDenialNeverFallsBack() {
        try (var fixture = new Fixture()) {
            fixture.redis.scriptLoad(RedisBoundedPointRead.SCRIPT);
            fixture.withUser("~" + fixture.prefix + "*", "-mget", client -> {
                assertThrows(JedisDataException.class, () -> fixture.read(client, 1024));
                assertEquals(1, client.shaCalls);
                assertEquals(0, client.evalCalls);
            });
        }
    }

    @Test void warmScriptBudgetAndWrongTypeFailuresAreNotRetried() {
        try (var fixture = new Fixture()) {
            fixture.redis.scriptLoad(RedisBoundedPointRead.SCRIPT);
            var client = new RedisBoundedPointReadTest.RecordingClient(fixture.operations);
            var budget = assertThrows(JedisDataException.class, () -> fixture.read(client, 2));
            assertTrue(budget.getMessage().contains("CACHEDB_READ_BUDGET"));
            fixture.redis.del(fixture.entity);
            fixture.redis.hset(fixture.entity, "field", "value");
            var wrongType = assertThrows(JedisDataException.class, () -> fixture.read(client, 1024));
            assertTrue(wrongType.getMessage().contains("WRONGTYPE"));
            assertEquals(2, client.shaCalls);
            assertEquals(0, client.evalCalls);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JedisPooled redis = new JedisPooled(URI.create(RedisTestEndpoint.uri()), 2000);
        final JedisPooledOperations operations = new JedisPooledOperations(redis);
        final String prefix = "bounded-sha-" + UUID.randomUUID();
        final String entity = prefix + ":{point}:e", tombstone = prefix + ":{point}:t";
        Fixture() {
            redis.setex(entity, 60, "\u00fc");
            redis.setex(tombstone, 60, "1");
        }
        RedisBoundedPointRead.Result read(RedisClientOperations client, long budget) {
            return RedisBoundedPointRead.readMeasured(client,
                    List.of(new RedisBoundedPointRead.KeyPair(entity, tombstone, true)), budget);
        }
        void assertRead(RedisClientOperations client) {
            var result = read(client, 3);
            assertEquals(3, result.contentBytes());
            assertEquals("\u00fc", result.reads()[0].payload());
            assertEquals("1", result.reads()[0].tombstone());
            assertTrue(result.reads()[0].ttlMillis() > 0);
        }
        void withUser(String keyRule, String commandRule,
                Consumer<RedisBoundedPointReadTest.RecordingClient> action) {
            String user = "bounded_sha_" + UUID.randomUUID().toString().replace("-", "");
            String password = UUID.randomUUID().toString();
            try {
                redis.sendCommand(Command.ACL, "SETUSER", user, "reset", "on", ">" + password,
                        keyRule, "+eval", "+evalsha", "+strlen", "+mget", "+pttl", "+ping", "+hello",
                        "+client|setinfo", commandRule);
                var endpoint = URI.create(RedisTestEndpoint.uri());
                var pool = new GenericObjectPoolConfig<Connection>();
                pool.setMaxTotal(1); pool.setMaxIdle(1); pool.setJmxEnabled(false);
                pool.setMaxWait(Duration.ofSeconds(2));
                try (var authenticated = new JedisPooled(new HostAndPort(endpoint.getHost(), endpoint.getPort()),
                        DefaultJedisClientConfig.builder().user(user).password(password)
                                .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build(), pool)) {
                    action.accept(new RedisBoundedPointReadTest.RecordingClient(new JedisPooledOperations(authenticated)));
                }
            } finally { redis.sendCommand(Command.ACL, "DELUSER", user); }
        }
        @Override public void close() {
            try { redis.del(entity, tombstone); }
            finally { redis.close(); }
        }
    }
}
