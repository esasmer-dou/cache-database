package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.codec.CacheValueCodec;
import com.reactor.cachedb.core.config.SingleFlightConfig;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.SimpleKey;

class CacheDbCacheContractTest {
    static CacheDbCacheConfiguration config(boolean tenant, boolean nullable) {
        return new CacheDbCacheConfiguration(Duration.ofMinutes(1), NearCachePolicy.disabled(), tenant, nullable, 4096);
    }

    @Test void requiredTtlAndAllHardLimitsAreEnforced() {
        assertThrows(NullPointerException.class, () -> new CacheDbCacheConfiguration(null, NearCachePolicy.disabled(), false, false, 1024));
        for (Duration ttl : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofDays(7).plusMillis(1)))
            assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheConfiguration(ttl, NearCachePolicy.disabled(), false, false, 1024));
        assertDoesNotThrow(() -> new CacheDbCacheConfiguration(Duration.ofDays(7), NearCachePolicy.disabled(), false, false, 16 * 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheConfiguration(Duration.ofSeconds(1),
                new NearCachePolicy(10, 2, 4096), false, false, 1024));
        assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheConfiguration(Duration.ofDays(1),
                NearCachePolicy.disabled(), false, false, 16 * 1024 * 1024 + 1));
        assertThrows(IllegalArgumentException.class, () -> new CacheDbSpringCacheProperties.NearSpec(1, 4096L,
                Duration.ofMillis(1), Duration.ZERO).policy());
        assertThrows(IllegalArgumentException.class, () -> new CacheDbSpringCacheProperties.NearSpec(1, 4096L,
                Duration.ofSeconds(2), Duration.ofSeconds(2)).policy());
    }

    @Test void keysAreTypedEncodedBoundedAndDoNotUseObjectToString() {
        assertNotEquals(CacheDbCacheKeys.encode(1), CacheDbCacheKeys.encode("1"));
        assertNotEquals(CacheDbCacheKeys.encode(1), CacheDbCacheKeys.encode(1L));
        assertNotEquals(CacheDbCacheKeys.encode("a:b"), CacheDbCacheKeys.encode("a/b"));
        assertEquals(CacheDbCacheKeys.encode(SimpleKey.EMPTY), CacheDbCacheKeys.encode(new SimpleKey()));
        assertThrows(IllegalArgumentException.class, () -> CacheDbCacheKeys.encode(new SimpleKey(1, "1")));
        assertThrows(IllegalArgumentException.class, () -> CacheDbCacheKeys.encode(new Object() {
            @Override public String toString() { throw new AssertionError("Must not call arbitrary toString"); }
        }));
        String longKey = "\u0131".repeat(2048);
        assertTrue(CacheDbCacheKeys.encode(longKey).startsWith("h:"));
        assertEquals(CacheDbCacheKeys.encode(longKey), CacheDbCacheKeys.encode(longKey));
        assertTrue(CacheDbCacheKeys.encode(longKey).length() <= 1024);
        assertThrows(IllegalArgumentException.class, () -> CacheDbCacheKeys.encode("x".repeat(65537)));
        assertThrows(IllegalArgumentException.class, () -> CacheDbCacheKeys.encode("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> CacheDbCacheKeys.prefix("bad:{slot}"));
        var generator = new CacheDbKeyGenerator();
        assertSame(SimpleKey.EMPTY, generator.generate(null, null));
        assertEquals(42, generator.generate(null, null, 42));
        assertEquals("native", generator.generate(null, null, "native"));
        Object composite = generator.generate(null, null, "a", "b");
        assertNotEquals(composite, generator.generate(null, null, composite),
                "A literal single String cannot collide with the reserved composite String domain");
        String longComposite = (String) generator.generate(null, null, "x".repeat(1200), 7);
        assertTrue(CacheDbCacheKeys.encode(longComposite).startsWith("h:"));
        assertNotEquals(generator.generate(null, null, 1, "1"), generator.generate(null, null, "1", 1));
        assertNotEquals(generator.generate(null, null, "a, b"), generator.generate(null, null, "a", "b"));
        assertNotEquals(generator.generate(null, null, (Object) null), generator.generate(null, null, "null"));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(null, null, new Object[129]));
    }

    @Test void tokenJsonCodecPreservesScalarTypesAndRejectsPojoPolymorphismAndMalformedWire() throws Exception {
        var codec = new CacheDbJsonCacheValueCodec(4096);
        for (Object value : List.of("hello \u0130\u011f", 'c', true, (byte) 1, (short) 2, 3, 4L,
                1.5f, 2.5d, BigInteger.TEN, new BigDecimal("2.00"), UUID.randomUUID())) {
            Object decoded = codec.decodeValue(codec.encodeValue(value));
            assertEquals(value, decoded);
            assertEquals(value.getClass(), decoded.getClass());
        }
        assertThrows(IOException.class, () -> codec.encodeValue(new Object()));
        assertThrows(IOException.class, () -> codec.encodeValue(Double.NaN));
        assertThrows(IOException.class, () -> codec.decodeValue("[\"@class\",\"java.lang.Runtime\"]"));
        assertThrows(IOException.class, () -> codec.decodeValue("[\"boolean\",\"not-true\"]"));
        assertThrows(IOException.class, () -> codec.decodeValue("[\"int\",\"1\"]{}"));
        assertThrows(IOException.class, () -> codec.decodeValue("[\"char\",\"ab\"]"));
        assertThrows(IOException.class, () -> new CacheDbJsonCacheValueCodec(8).encodeValue("over budget"));
    }

    @Test void everyTenantOperationFailsClosedBeforeTouchingRedisIncludingAdministrativeScopes() {
        var redis = mock(RedisClientOperations.class);
        try (var manager = new CacheDbCacheManager(redis, "test", Map.of("tenant", config(true, true)), List.of())) {
            var cache = manager.getCache("tenant");
            List<Runnable> calls = List.of(() -> cache.get("k"), () -> cache.get("k", String.class),
                    () -> cache.get("k", () -> "value"), () -> cache.put("k", "v"),
                    () -> cache.putIfAbsent("k", "v"), () -> cache.evict("k"), () -> cache.evictIfPresent("k"),
                    cache::clear, cache::invalidate);
            for (Runnable call : calls) assertThrows(CacheTenantAccessDeniedException.class, call::run);
            try (var ignored = CacheTenantContext.administrative()) {
                for (Runnable call : calls) assertThrows(CacheTenantAccessDeniedException.class, call::run);
            }
            verifyNoInteractions(redis);
        }
    }

    @Test void entityCausalRequirementsAreNotSilentlyIgnored() {
        var redis = mock(RedisClientOperations.class);
        try (var manager = new CacheDbCacheManager(redis, "test", Map.of("cache", config(false, true)), List.of());
             var context = CacheCausalContext.withMinVersion("entity", "id", 42)) {
            var cache = manager.getCache("cache");
            List<Runnable> calls = List.of(() -> cache.get("k"), () -> cache.get("k", String.class),
                    () -> cache.get("k", () -> "stale"), () -> cache.put("k", "v"),
                    () -> cache.putIfAbsent("k", "v"), () -> cache.evict("k"), () -> cache.evictIfPresent("k"),
                    cache::clear, cache::invalidate);
            for (Runnable call : calls) assertThrows(IllegalStateException.class, call::run);
            verifyNoInteractions(redis);
        }
    }

    @Test void cacheNamesSchemasAndAggregateBudgetsAreFixedBeforeSubscriptionsStart() {
        var redis = mock(RedisClientOperations.class);
        var policy = new CacheDbCacheConfiguration(Duration.ofMinutes(1),
                new NearCachePolicy(40000, 1, 40L * 1024 * 1024), false, false, 4096);
        assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheManager(redis, "test",
                Map.of("one", policy, "two", policy), List.of()));
        CacheValueCodec<String> codec = new CacheValueCodec<>() {
            public String encodeValue(String value) { return value; }
            public String decodeValue(String value) { return value; }
        };
        assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheManager(redis, "test",
                Map.of("one", config(false, false)), List.of(new CacheDbCacheCodec<>("unknown", codec))));
        assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheManager(redis, "test",
                Map.of("one", config(false, false)), List.of(new CacheDbCacheCodec<>("one", codec), new CacheDbCacheCodec<>("one", codec))));
        try (var manager = new CacheDbCacheManager(redis, "test", Map.of("one", config(false, false)), List.of())) {
            assertNull(manager.getCache("unknown"));
            assertThrows(UnsupportedOperationException.class, () -> manager.getCacheNames().clear());
            manager.close();
            manager.close();
            assertThrows(IllegalStateException.class, () -> manager.getCache("one"));
            verifyNoInteractions(redis);
        }
    }

    @Test void operationBudgetRejectsInsteadOfQueueingAndCloseNeverClosesBorrowedClient() throws Exception {
        var redis = mock(RedisClientOperations.class);
        var manager = new CacheDbCacheManager(redis, redis, "test", Map.of("cache", config(false, false)),
                List.of(), 1, new SingleFlightConfig(true, 1, 2, 50));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread worker = Thread.startVirtualThread(() -> manager.operation(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            return null;
        }));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> manager.operation(() -> "no"));
            assertEquals(1, manager.rejectedOperations());
        } finally { release.countDown(); worker.join(5000); manager.close(); }
        assertFalse(worker.isAlive());
        verify(redis, never()).close();
    }

    @Test void futureRetrieveOverloadsAreExplicitlyUnsupportedAndNeverCallRedisOrTheLoader() {
        var redis = mock(RedisClientOperations.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var manager = new CacheDbCacheManager(redis, "retrieve", Map.of("cache", config(false, true)), List.of())) {
            var cache = manager.getCache("cache");
            assertThrows(UnsupportedOperationException.class, () -> cache.retrieve("key"));
            assertThrows(UnsupportedOperationException.class, () -> cache.retrieve("key", () -> {
                calls.incrementAndGet();
                return java.util.concurrent.CompletableFuture.completedFuture("value");
            }));
            assertEquals(0, calls.get());
            verifyNoInteractions(redis);
        }
    }
}
