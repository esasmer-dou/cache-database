package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.codec.CacheValueCodec;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.Cache;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(45)
class CacheDbCacheRedisTest {
    static CacheDbCacheConfiguration config(boolean tenant, boolean nullable, boolean near) {
        return CacheDbCacheRedisFixture.config(tenant, nullable, near, Duration.ofSeconds(10), 4096);
    }

    static CacheDbCacheConfiguration slowNear(boolean tenant) {
        return new CacheDbCacheConfiguration(Duration.ofMinutes(2),
                new com.reactor.cachedb.core.cache.NearCachePolicy(128, 30, 1024 * 1024), tenant, true, 4096);
    }

    @Test void hitMissTypedValuesNullsAtomicPutIfAbsentAndEvict() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var manager = fixture.manager(Map.of("nullable", config(false, true, false), "strict", config(false, false, false)));
            var cache = manager.getCache("nullable");
            assertNull(cache.get("key"));
            cache.put("key", "one");
            assertEquals("one", cache.get("key", String.class));
            assertThrows(IllegalStateException.class, () -> cache.get("key", Integer.class));
            assertEquals("one", cache.putIfAbsent("key", "two").get());
            assertEquals("one", cache.get("key").get());
            assertTrue(cache.evictIfPresent("key"));
            assertFalse(cache.evictIfPresent("key"));
            assertNull(cache.putIfAbsent("key", null));
            assertNotNull(cache.get("key"));
            assertNull(cache.get("key").get());
            AtomicInteger calls = new AtomicInteger();
            assertNull(cache.get("key", () -> { calls.incrementAndGet(); return "wrong"; }));
            assertEquals(0, calls.get());
            cache.put(1, 123L);
            cache.put("1", 456);
            assertEquals(123L, cache.get(1, Long.class));
            assertEquals(456, cache.get("1", Integer.class));
            var strict = manager.getCache("strict");
            assertThrows(IllegalArgumentException.class, () -> strict.put("n", null));
            assertNull(strict.get("n", () -> null));
            assertNull(strict.get("n"));
            assertEquals("later", strict.get("n", () -> "later"));
        }
    }

    @Test void clearUsesGenerationIncrementLeavesOldPayloadToExpireAndDoesNotScan() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var cache = fixture.manager(Map.of("cache", config(false, false, false))).getCache("cache");
            cache.put("key", "old");
            String generation = fixture.ownedKeys.stream().filter(k -> k.endsWith(":generation")).findFirst().orElseThrow();
            String oldPayload = fixture.ownedKeys.stream().filter(k -> k.contains(":g") && !k.endsWith(":generation")).findFirst().orElseThrow();
            long before = Long.parseLong(fixture.underlying.commands().get(generation));
            cache.clear();
            assertEquals(before + 1, Long.parseLong(fixture.underlying.commands().get(generation)));
            assertNull(cache.get("key"));
            assertTrue(fixture.underlying.commands().exists(oldPayload));
            assertTrue(fixture.underlying.commands().pttl(oldPayload) > 0);
            cache.put("key", "new");
            assertEquals("new", cache.get("key").get());
            for (String script : fixture.scripts) {
                assertFalse(script.contains("redis.call('SCAN'"));
                assertFalse(script.contains("redis.call('KEYS'"));
            }
        }
    }

    @Test void tenantClearEvictLoadAndPutAreIsolatedAndSameNamesInDifferentManagersAgree() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var configs = Map.of("tenant", config(true, true, false));
            var a = fixture.manager(configs).getCache("tenant");
            var b = fixture.manager(configs).getCache("tenant");
            try (var ignored = CacheTenantContext.withTenant("a:{foreign}:x")) { a.put("key", "tenant-a"); }
            try (var ignored = CacheTenantContext.withTenant("b")) {
                assertNull(b.get("key"));
                assertEquals("tenant-b", b.get("key", () -> {
                    assertEquals("b", CacheTenantContext.currentTenant().orElseThrow());
                    return "tenant-b";
                }));
            }
            try (var ignored = CacheTenantContext.withTenant("a:{foreign}:x")) {
                assertEquals("tenant-a", b.get("key").get());
                a.clear();
                assertNull(b.get("key"));
            }
            try (var ignored = CacheTenantContext.withTenant("b")) {
                assertEquals("tenant-b", a.get("key").get());
                assertTrue(b.evictIfPresent("key"));
            }
            assertThrows(RuntimeException.class, () -> a.get("key"));
        }
    }

    @Test void expiryIsMandatoryAndNearCacheNeverOutlivesRedisTtl() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var manager = fixture.manager(Map.of("cache", CacheDbCacheRedisFixture.config(false, false, true,
                    Duration.ofSeconds(1), 4096)));
            CacheDbCacheRedisFixture.await(manager::invalidationConnected);
            var cache = manager.getCache("cache");
            cache.put("key", "short-lived");
            assertEquals("short-lived", cache.get("key").get());
            CacheDbCacheRedisFixture.await(() -> cache.get("key") == null);
            assertNull(cache.get("key"));
        }
    }

    @Test void peerPutEvictAndGenerationClearInvalidateWarmL1CopiesWithZeroRttHits() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var configs = Map.of("cache", slowNear(false));
            var one = fixture.manager(configs);
            var two = fixture.manager(configs);
            CacheDbCacheRedisFixture.await(() -> one.invalidationConnected() && two.invalidationConnected());
            var a = one.getCache("cache");
            var b = two.getCache("cache");
            a.put("key", "old");
            CacheDbCacheRedisFixture.await(() -> {
                assertEquals("old", b.get("key").get());
                return b.statistics().nearHits() > 0;
            });
            long evaluations = fixture.evalCalls.get(), clients = fixture.commandsCalls.get();
            for (int i = 0; i < 1000; i++) {
                assertEquals("old", b.get("key", String.class));
                assertEquals("old", b.get("key", () -> { throw new AssertionError("L1 must not run the loader"); }));
            }
            assertEquals(evaluations, fixture.evalCalls.get(), "Warm L1 hit must not read generation/revision metadata");
            assertEquals(clients, fixture.commandsCalls.get(), "Warm L1 must not even access the Redis client");
            long beforeMiss = fixture.evalCalls.get();
            assertNull(b.get("different-key"));
            assertEquals(1, fixture.evalCalls.get() - beforeMiss, "Known-generation miss reads metadata + payload in one EVAL");
            String revisionKey = fixture.ownedKeys.stream()
                    .filter(key -> key.endsWith(":revision:" + CacheDbCacheKeys.encode("key"))).findFirst().orElseThrow();
            two.invalidationCoordinator().trackingInvalidated(revisionKey);
            long refreshed = b.statistics().nearHits();
            CacheDbCacheRedisFixture.await(() -> {
                assertEquals("old", b.get("key", String.class));
                return b.statistics().nearHits() > refreshed;
            });
            a.put("key", "updated");
            CacheDbCacheRedisFixture.await(() -> "updated".equals(b.get("key", String.class)));
            assertTrue(two.invalidationCoordinator().receivedNotifications() > 0);
            a.evict("key");
            CacheDbCacheRedisFixture.await(() -> b.get("key") == null);
            a.put("key", "again");
            long nearBefore = b.statistics().nearHits();
            CacheDbCacheRedisFixture.await(() -> {
                assertEquals("again", b.get("key").get());
                return b.statistics().nearHits() > nearBefore;
            });
            long clearBefore = fixture.evalCalls.get();
            a.clear();
            assertEquals(1, fixture.evalCalls.get() - clearBefore, "Clear must be one INCR + publish script, with no generation prefetch");
            CacheDbCacheRedisFixture.await(() -> b.get("key") == null);
            assertFalse(two.invalidationCoordinator().generationScopes() > 4096);
            two.close();
            assertThrows(IllegalStateException.class, () -> b.get("key"));
            assertEquals("PONG", fixture.underlying.commands().ping());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"clear", "put", "evict"})
    void inFlightLoaderCannotResurrectDataAfterSameKeyMutationOrClear(String mutation) throws Exception {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var configs = Map.of("cache", config(false, false, true));
            var a = fixture.manager(configs).getCache("cache");
            var b = fixture.manager(configs).getCache("cache");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Future<String> pending = tasks.submit(() -> a.get("key", () -> {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test loader timed out");
                return "stale";
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                switch (mutation) {
                    case "clear" -> b.clear();
                    case "put" -> b.put("key", "new");
                    case "evict" -> b.evict("key");
                    default -> fail("Unexpected fixture mode");
                }
            } finally { release.countDown(); }
            ExecutionException failed = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ConcurrentModificationException.class, failed.getCause());
            if (mutation.equals("put")) assertEquals("new", a.get("key").get());
            else assertNull(a.get("key"));
            assertEquals(0, fixture.managers.get(0).flights().inFlightCount());
            assertEquals(1, a.statistics().fencedLoads());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"generation", "state"})
    void disappearingMetadataCreatesANewIncarnationInsteadOfResurrectingGenerationZero(String missing) {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var cache = fixture.manager(Map.of("cache", config(false, false, false))).getCache("cache");
            cache.put("key", "generation-zero");
            cache.clear();
            cache.put("key", "generation-one");
            String state = fixture.ownedKeys.stream().filter(k -> k.endsWith(":state")).findFirst().orElseThrow();
            String before = fixture.underlying.commands().get(state);
            String missingKey = fixture.ownedKeys.stream().filter(k -> k.endsWith(":" + missing)).findFirst().orElseThrow();
            fixture.underlying.commands().del(missingKey);
            assertNull(cache.get("key"), "Losing either metadata key must never reuse an old incarnation");
            assertNotEquals(before, fixture.underlying.commands().get(state));
            cache.put("key", "fresh");
            assertEquals("fresh", cache.get("key").get());
        }
    }

    @Test void oversizedUtf8ValuesAndOversizedStoredPayloadsFailWithoutReturningPartialData() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var cache = fixture.manager(Map.of("cache", config(false, false, false))).getCache("cache");
            assertThrows(IllegalArgumentException.class, () -> cache.put("key", "\u011f".repeat(3000)));
            assertNull(cache.get("key"));
            assertThrows(IllegalArgumentException.class, () -> cache.get("key", () -> "x".repeat(5000)));
            assertNull(cache.get("key"));
            cache.put("key", "small");
            String generationKey = fixture.ownedKeys.stream().filter(k -> k.endsWith(":generation"))
                    .findFirst().orElseThrow();
            String stem = generationKey.substring(0, generationKey.length() - ":generation".length());
            var metadata = fixture.underlying.commands().mget(stem + ":state", generationKey);
            assertNotNull(metadata.get(0));
            assertNotNull(metadata.get(1));
            var active = new CacheDbCacheStore.Generation(metadata.get(0), metadata.get(1));
            String payload = active.valueKey(new CacheDbCacheStore.Scope("cache", "", stem),
                    CacheDbCacheKeys.encode("key"));
            assertTrue(fixture.ownedKeys.contains(payload), "The active value key must have been used by the store");
            assertNotNull(fixture.underlying.commands().get(payload), "Corrupt the existing active value, not a failed hint probe");
            String oversized = "1" + "x".repeat(4999);
            fixture.underlying.commands().set(payload, oversized,
                    redis.clients.jedis.params.SetParams.setParams().px(10000));
            assertEquals(5000L, fixture.underlying.commands().strlen(payload));
            assertEquals(oversized, fixture.underlying.commands().get(payload));
            var failure = assertThrows(redis.clients.jedis.exceptions.JedisDataException.class, () -> cache.get("key"));
            assertTrue(failure.getMessage().contains("Spring Cache value byte limit exceeded"));
        }
    }

    @Test void failedAndRecursiveLoadersReleaseSingleFlightAndFailuresAreNotCached() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var manager = fixture.manager(Map.of("cache", config(false, false, false)));
            var cache = manager.getCache("cache");
            assertThrows(Cache.ValueRetrievalException.class, () -> cache.get("key", () -> { throw new java.io.IOException("source failed"); }));
            assertEquals(0, manager.flights().inFlightCount());
            assertNull(cache.get("key"));
            assertThrows(Cache.ValueRetrievalException.class, () -> cache.get("key", () -> cache.get("key", () -> "recursive")));
            assertEquals(0, manager.flights().inFlightCount());
            assertEquals("recovered", cache.get("key", () -> "recovered"));
        }
    }

    @Test void concurrentPutIfAbsentHasExactlyOneWinner() throws Exception {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var cache = fixture.manager(Map.of("cache", config(false, false, false))).getCache("cache");
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Cache.ValueWrapper>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String value = "value-" + i;
                results.add(tasks.submit(() -> { start.await(); return cache.putIfAbsent("key", value); }));
            }
            start.countDown();
            int winners = 0;
            Object actual = null;
            for (var result : results) {
                var previous = result.get(10, TimeUnit.SECONDS);
                if (previous == null) winners++;
                else if (actual == null) actual = previous.get();
                else assertEquals(actual, previous.get());
            }
            assertEquals(1, winners);
            assertEquals(actual, cache.get("key").get());
        }
    }

    @Test void explicitGeneratedStyleCodecIsUsedWithoutPojoDatabindingAndDecodeIsNotShared() {
        record Message(String text) {}
        CacheValueCodec<Message> codec = new com.reactor.cachedb.json.StreamingJsonCodec<Message>(
                new com.reactor.cachedb.json.JsonCodecLimits(4, 1, 64, 4096)) {
            public Class<Message> valueType() { return Message.class; }
            public Message readValue(com.google.gson.stream.JsonReader reader,
                    com.reactor.cachedb.json.JsonCodecLimits limits) throws java.io.IOException {
                reader.beginObject();
                if (!reader.nextName().equals("text")) throw new java.io.IOException("Invalid field");
                String text = com.reactor.cachedb.json.JsonValues.string(reader, limits);
                if (reader.hasNext()) throw new java.io.IOException("Unexpected field");
                reader.endObject();
                return new Message(text);
            }
            public void writeValue(com.google.gson.stream.JsonWriter writer, Message value,
                    com.reactor.cachedb.json.JsonCodecLimits limits) throws java.io.IOException {
                writer.beginObject();
                writer.name("text");
                com.reactor.cachedb.json.JsonValues.string(writer, value.text(), limits);
                writer.endObject();
            }
        };
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var cache = fixture.manager(Map.of("cache", config(false, false, false)),
                    List.of(new CacheDbCacheCodec<>("cache", codec))).getCache("cache");
            cache.put("key", new Message("explicit schema"));
            var one = cache.get("key", Message.class);
            var two = cache.get("key", Message.class);
            assertEquals(one, two);
            assertNotSame(one, two);
        }
    }

    @Test void unrelatedKeyMutationDoesNotFenceOrInvalidateTheSourceKey() throws Exception {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var configs = Map.of("cache", config(false, false, false));
            var a = fixture.manager(configs).getCache("cache");
            var b = fixture.manager(configs).getCache("cache");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Future<String> source = tasks.submit(() -> a.get("source-key", () -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return "source-value";
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                b.put("unrelated-key", "other-value");
            } finally { release.countDown(); }
            assertEquals("source-value", source.get(10, TimeUnit.SECONDS));
            assertEquals("source-value", b.get("source-key").get());
            assertEquals(0, a.statistics().fencedLoads());
        }
    }


    @Test void explicitCodecMayEncodeAnEmptyNonNullValueWithinTheOneByteEnvelopeBudget() {
        CacheValueCodec<String> identity = new CacheValueCodec<>() {
            public String encodeValue(String value) { return value; }
            public String decodeValue(String value) { return value; }
        };
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var config = CacheDbCacheRedisFixture.config(false, false, false, Duration.ofSeconds(10), 1);
            var cache = fixture.manager(Map.of("empty", config),
                    List.of(new CacheDbCacheCodec<>("empty", identity))).getCache("empty");
            cache.put("key", "");
            assertNotNull(cache.get("key"));
            assertEquals("", cache.get("key", String.class));
            assertThrows(IllegalArgumentException.class, () -> cache.put("key", "x"));
            assertEquals("", cache.get("key", String.class));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"read", "put", "nx", "load", "check", "evict", "clear"})
    void allRedisPathsRenewFiniteScopeLeasesAndNeverCreatePersistentMetadata(String operation) {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            var config = CacheDbCacheRedisFixture.config(true, false, false, Duration.ofSeconds(2), 4096);
            var cache = fixture.manager(Map.of("cache", config)).getCache("cache");
            try (var tenant = CacheTenantContext.withTenant("lease-tenant")) {
                cache.put("key", "value");
                List<String> scope = fixture.ownedKeys.stream()
                        .filter(k -> k.endsWith(":generation") || k.endsWith(":state")).toList();
                assertEquals(2, scope.size());
                // Five seconds still exceeds every two-second payload; shorten only the test lease.
                for (String key : scope) assertEquals(1, fixture.underlying.commands().pexpire(key, 5000));
                switch (operation) {
                    case "read" -> assertEquals("value", cache.get("key", String.class));
                    case "put" -> cache.put("key", "new");
                    case "nx" -> assertNull(cache.putIfAbsent("new", "new"));
                    case "load" -> assertEquals("loaded", cache.get("new", () -> "loaded"));
                    case "check" -> assertNull(cache.get("new", () -> null));
                    case "evict" -> assertTrue(cache.evictIfPresent("key"));
                    case "clear" -> cache.clear();
                    default -> fail("Unknown test operation");
                }
                for (String key : scope) {
                    long ttl = fixture.underlying.commands().pttl(key);
                    assertTrue(ttl > 55000 && ttl <= 62000, "Scope lease must renew to value TTL plus 60 seconds");
                }
                for (String key : fixture.ownedKeys) {
                    long ttl = fixture.underlying.commands().pttl(key);
                    assertNotEquals(-1, ttl, "No adapter-created key may be persistent");
                    if (ttl > 0) assertTrue(ttl <= 62000);
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deniedExpiryCannotLeaveNewPersistentStateDuringPutOrClear(boolean clear) {
        try (var fixture = CacheDbCacheRedisFixture.standalone();
             var admin = new redis.clients.jedis.Jedis(
                     System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                     Integer.getInteger("cachedb.snapshot.redis.port", 16383), 5000)) {
            String user = "sc" + java.util.UUID.randomUUID().toString().replace("-", "");
            String password = java.util.UUID.randomUUID().toString().replace("-", "");
            assertEquals("OK", admin.aclSetUser(user, "reset", "on", ">" + password,
                    "~" + fixture.prefix + "*", "&" + fixture.prefix + "*",
                    "+eval", "+exists", "+set", "+strlen", "+get", "+incr", "+publish", "+ping", "+client"));
            try (var limited = new redis.clients.jedis.JedisPooled("redis://" + user + ":" + password + "@"
                    + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":"
                    + Integer.getInteger("cachedb.snapshot.redis.port", 16383))) {
                var restricted = com.reactor.cachedb.redis.JedisPooledOperations.of(limited);
                com.reactor.cachedb.redis.RedisClientOperations primary = new com.reactor.cachedb.redis.RedisClientOperations() {
                    public redis.clients.jedis.UnifiedJedis commands() { return restricted.commands(); }
                    public Object eval(String script, List<String> keys, List<String> arguments) {
                        fixture.ownedKeys.addAll(keys);
                        return restricted.eval(script, keys, arguments);
                    }
                };
                try (var manager = new CacheDbCacheManager(primary, fixture.tracked, fixture.prefix,
                        Map.of("cache", CacheDbCacheRedisFixture.config(false, false, false,
                                Duration.ofSeconds(2), 4096)), List.of(), 8,
                        new com.reactor.cachedb.core.config.SingleFlightConfig(true, 8, 8, 5000))) {
                    var cache = manager.getCache("cache");
                    assertThrows(RuntimeException.class, () -> {
                        if (clear) cache.clear(); else cache.put("key", "must-not-be-stored");
                    });
                    for (String key : fixture.ownedKeys) {
                        long ttl = fixture.underlying.commands().pttl(key);
                        assertNotEquals(-1, ttl, "Even failed creation must not leave persistent metadata");
                        if (ttl > 0) assertTrue(ttl <= 62000);
                    }
                    assertEquals(2, fixture.ownedKeys.stream()
                            .filter(k -> k.endsWith(":generation") || k.endsWith(":state"))
                            .filter(k -> fixture.underlying.commands().exists(k)).count());
                }
            } finally { assertEquals(1, admin.aclDelUser(user)); }
        }
    }
}
