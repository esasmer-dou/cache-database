package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;

/** Existing approved Redis fixture only; no server, ACL user, TLS material or database provisioning. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class BootstrapUriRedisClientFactoryRedisTest {
    private static String uri() {
        String configured = System.getProperty("cachedb.bootstrap.redis.uri");
        if (configured != null && !configured.isBlank()) return configured;
        return "redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":"
                + Integer.getInteger("cachedb.snapshot.redis.port", 16383) + "/14?protocol=3";
    }

    @Test @Timeout(15) void actualProtocolAuthAndSelectedDatabaseMatchOriginalUriClient() {
        String uri = uri();
        var expected = BootstrapUriRedisClientFactory.clientConfig(java.net.URI.create(uri));
        try (var original = new JedisPooled(uri); var fair = BootstrapUriRedisClientFactory.create(uri)) {
            assertEquals("PONG", original.ping()); assertEquals("PONG", fair.ping());
            assertFalse(original.getPool().getFairness()); assertTrue(fair.getPool().getFairness());
            try (Connection old = original.getPool().getResource(); Connection current = fair.getPool().getResource()) {
                assertEquals(expected.getRedisProtocol(), old.getRedisProtocol());
                assertEquals(old.getRedisProtocol(), current.getRedisProtocol());
                assertEquals(2000, old.getSoTimeout()); assertEquals(old.getSoTimeout(), current.getSoTimeout());
                var before = clientInfo(old); var after = clientInfo(current);
                for (String field : List.of("db", "resp", "user", "name"))
                    assertEquals(before.get(field), after.get(field), field);
                assertEquals(Integer.toString(expected.getDatabase()), after.get("db"));
                assertEquals(expected.getRedisProtocol() == null ? "2" : expected.getRedisProtocol().version(), after.get("resp"));
                assertEquals(expected.getUser() == null ? "default" : expected.getUser(), after.get("user"));
            }
            assertEquals(0, original.getPool().getNumActive()); assertEquals(0, fair.getPool().getNumActive());
        }
    }

    @Test @Timeout(20) void originalInfiniteBlockingTimeoutIsNotReplacedByReadTimeoutOrCacheDbFifteenSeconds() {
        String key = "bootstrap-uri-blocking-" + UUID.randomUUID();
        try (var original = new JedisPooled(uri()); var fair = BootstrapUriRedisClientFactory.create(uri())) {
            for (var client : List.of(original, fair)) {
                long started = System.nanoTime();
                // Server-side3s timeout exceeds the unchanged2s ordinary read timeout.
                assertNull(client.blpop(3, key));
                assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(2));
                assertEquals("PONG", client.ping(), "Blocking command must restore the ordinary socket state");
            }
        }
    }

    private static Map<String, String> clientInfo(Connection connection) {
        connection.sendCommand(Command.CLIENT, "INFO");
        Object raw = connection.getOne();
        assertInstanceOf(byte[].class, raw);
        var result = new HashMap<String, String>();
        for (String token : new String((byte[]) raw, StandardCharsets.UTF_8).strip().split("\\s+")) {
            int equals = token.indexOf('=');
            if (equals > 0) result.put(token.substring(0, equals), token.substring(equals + 1));
        }
        return result;
    }
}
