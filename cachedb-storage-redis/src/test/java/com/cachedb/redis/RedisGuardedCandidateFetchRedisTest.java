package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.query.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.exceptions.JedisDataException;

@Timeout(60)
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisGuardedCandidateFetchRedisTest {
    private static final String NAMESPACE = "t4_rows";

    @Test void realGuardDistinguishesHealthyEmptyAndDegradedBeforeWrongtypeMembership() {
        try (var fixture = new Fixture()) {
            var empty = fixture.operations.guardedSetMembers(fixture.health, fixture.members, 2).orElseThrow();
            assertFalse(empty.degraded());
            assertTrue(empty.members().isEmpty());
            fixture.redis.sadd(fixture.members, "1", "2");
            assertEquals(Set.of("1", "2"), Set.copyOf(fixture.operations
                    .guardedSetMembers(fixture.health, fixture.members, 2).orElseThrow().members()));
            fixture.redis.set(fixture.health, "1");
            fixture.redis.del(fixture.members);
            fixture.redis.set(fixture.members, "wrongtype");
            var degraded = fixture.operations.guardedSetMembers(fixture.health, fixture.members, 2).orElseThrow();
            assertTrue(degraded.degraded());
            assertTrue(degraded.members().isEmpty());
            fixture.redis.del(fixture.health);
            assertThrows(JedisDataException.class,
                    () -> fixture.operations.guardedSetMembers(fixture.health, fixture.members, 2));
        }
    }

    @Test void realCardinalityCapRejectsBeforeReadingMembership() {
        try (var fixture = new Fixture()) {
            fixture.redis.sadd(fixture.members, "1", "2", "3");
            withUser(fixture, List.of("~" + fixture.prefix + ":*"), "-smembers", operations -> {
                var failure = assertThrows(JedisDataException.class,
                        () -> operations.guardedSetMembers(fixture.health, fixture.members, 2));
                assertTrue(failure.getMessage().contains("materialized membership budget exceeded"));
                fixture.redis.srem(fixture.members, "3");
                assertThrows(JedisDataException.class,
                        () -> operations.guardedSetMembers(fixture.health, fixture.members, 2));
            });
        }
    }

    @Test void realVerifiedEvalDenialDeclinesButKeyAndScriptCommandDenialsPropagate() {
        try (var fixture = new Fixture()) {
            fixture.redis.sadd(fixture.members, "1");
            fixture.redis.sadd(fixture.tenantA, "1");
            withUser(fixture, List.of("~" + fixture.prefix + ":*"), "-eval", operations -> {
                assertTrue(operations.guardedSetMembers(fixture.health, fixture.members, 2).isEmpty());
                var manager = manager(operations, fixture.keys);
                assertEquals(List.of("1"), manager.indexOnlyCandidates(QuerySpec.allOf()).orElseThrow());
                assertEquals(List.of("1"), manager.indexOnlyCandidates(QuerySpec.where(QueryFilter.eq("tenant", "a"))).orElseThrow());
            });
            withUser(fixture, List.of("~" + fixture.health), "+eval", operations ->
                    assertThrows(JedisDataException.class,
                            () -> operations.guardedSetMembers(fixture.health, fixture.members, 2)));
            withUser(fixture, List.of("~" + fixture.prefix + ":*"), "-scard", operations ->
                    assertThrows(JedisDataException.class,
                            () -> operations.guardedSetMembers(fixture.health, fixture.members, 2)));
            withUser(fixture, List.of("~" + fixture.prefix + ":*"), "-exists", operations ->
                    assertThrows(JedisDataException.class,
                            () -> operations.guardedSetMembers(fixture.health, fixture.members, 2)));
        }
    }

    @Test void realManagerMatchesLegacyGlobalAndTenantCandidateSets() {
        try (var fixture = new Fixture()) {
            fixture.redis.sadd(fixture.members, "1", "2");
            fixture.redis.sadd(fixture.tenantA, "1");
            fixture.redis.sadd(fixture.tenantB, "2");
            var legacy = new RedisClientOperations() {
                public UnifiedJedis commands() { return fixture.redis; }
            };
            for (QuerySpec query : List.of(QuerySpec.builder().build(), QuerySpec.allOf(),
                    QuerySpec.where(QueryGroup.or(QueryGroup.and())),
                    QuerySpec.where(QueryFilter.eq("tenant", "a")),
                    QuerySpec.where(QueryFilter.eq("tenant", "b")),
                    QuerySpec.allOf(QueryFilter.eq("tenant", "a")),
                    QuerySpec.anyOf(QueryFilter.eq("tenant", "b")))) {
                var actual = manager(fixture.operations, fixture.keys).indexOnlyCandidates(query).orElseThrow();
                var expected = manager(legacy, fixture.keys).indexOnlyCandidates(query).orElseThrow();
                assertEquals(Set.copyOf(expected), Set.copyOf(actual));
            }
        }
    }

    private static RedisQueryIndexManager<RedisT4AggregationRedisTest.Row, Long> manager(
            RedisClientOperations operations, RedisKeyStrategy keys) {
        return new RedisQueryIndexManager<>(operations, RedisT4AggregationRedisTest.metadata(true),
                RedisT4AggregationRedisTest.CODEC, keys, null, QueryIndexConfig.defaults(),
                RelationConfig.defaults(), new QueryEvaluator(), null);
    }

    private static void withUser(Fixture fixture, List<String> keyRules, String commandRule,
            Consumer<JedisPooledOperations> action) {
        String user = "guarded_fetch_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        var rules = new ArrayList<>(List.of("SETUSER", user, "reset", "on", ">" + password,
                "+@read", "+eval", "+ping", "+hello", "+client|setinfo"));
        rules.addAll(keyRules);
        rules.add(commandRule);
        boolean created = false;
        try {
            fixture.redis.sendCommand(Command.ACL, rules.toArray(String[]::new));
            created = true;
            var endpoint = URI.create(RedisTestEndpoint.uri());
            var config = new GenericObjectPoolConfig<Connection>();
            config.setMaxTotal(1); config.setMaxIdle(1); config.setJmxEnabled(false);
            config.setMaxWait(Duration.ofSeconds(2));
            try (var client = new JedisPooled(new HostAndPort(endpoint.getHost(), endpoint.getPort()),
                    DefaultJedisClientConfig.builder().user(user).password(password)
                            .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build(), config)) {
                action.accept(new JedisPooledOperations(client));
                assertEquals(0, client.getPool().getNumActive());
            }
        } finally {
            if (created) fixture.redis.sendCommand(Command.ACL, "DELUSER", user);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JedisPooled redis = new JedisPooled(URI.create(RedisTestEndpoint.uri()), 2000);
        final String prefix = "guarded-fetch-" + UUID.randomUUID();
        final RedisKeyStrategy keys = new RedisKeyStrategy(prefix);
        final String health = keys.indexDegradedKey(NAMESPACE), members = keys.indexAllKey(NAMESPACE);
        final String tenantA = keys.indexExactKey(NAMESPACE, "tenant", RedisIndexKeySpace.encodeKeyPart("a"));
        final String tenantB = keys.indexExactKey(NAMESPACE, "tenant", RedisIndexKeySpace.encodeKeyPart("b"));
        final JedisPooledOperations operations = new JedisPooledOperations(redis);
        public void close() {
            try { redis.del(health, members, tenantA, tenantB); }
            finally { redis.close(); }
        }
    }
}
