package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.core.config.SingleFlightConfig;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.parallel.Isolated
class CacheDbCacheJvmFlightsTest {
    @Test void sharedClientAndPrefixShareOneBoundedLoaderButSeparateClientsNeverShareData() {
        var firstClient = mock(RedisClientOperations.class);
        var otherClient = mock(RedisClientOperations.class);
        var config = Map.of("cache", CacheDbCacheContractTest.config(false, false));
        try (var first = new CacheDbCacheManager(firstClient, "shared", config, List.of());
             var second = new CacheDbCacheManager(firstClient, "shared", config, List.of());
             var isolated = new CacheDbCacheManager(otherClient, "shared", config, List.of());
             var otherPrefix = new CacheDbCacheManager(firstClient, "other", config, List.of())) {
            assertSame(first.flights(), second.flights());
            assertNotSame(first.flights(), isolated.flights());
            assertNotSame(first.flights(), otherPrefix.flights());
            verifyNoInteractions(firstClient, otherClient);
        }
    }

    @Test void conflictingGroupLimitsFailClosedAndLastCloseReleasesTheGroup() {
        var redis = mock(RedisClientOperations.class);
        var configs = Map.of("cache", CacheDbCacheContractTest.config(false, false));
        try (var first = new CacheDbCacheManager(redis, redis, "shared", configs, List.of(), 8,
                new SingleFlightConfig(true, 8, 8, 1000))) {
            assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheManager(redis, redis, "shared",
                    configs, List.of(), 8, new SingleFlightConfig(true, 9, 8, 1000)));
            assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheManager(redis, redis, "shared",
                    Map.of("cache", CacheDbCacheContractTest.config(true, false)), List.of(), 8,
                    new SingleFlightConfig(true, 8, 8, 1000)));
        }
        try (var replacement = new CacheDbCacheManager(redis, redis, "shared", configs, List.of(), 8,
                new SingleFlightConfig(true, 9, 8, 1000))) {
            assertNotNull(replacement.getCache("cache"));
            replacement.close();
            replacement.close();
        }
        verifyNoInteractions(redis);
    }

    @Test void jvmGroupCapacityRejectsBeforeAnySubscriptionOrIoAndLeasesAreReusable() {
        var redis = mock(RedisClientOperations.class);
        var configs = Map.of("cache", CacheDbCacheContractTest.config(false, false));
        List<CacheDbCacheManager> managers = new java.util.ArrayList<>();
        try {
            // Reserve the full explicit 65,536-key JVM budget using sixteen groups.
            for (int i = 0; i < 16; i++)
                managers.add(new CacheDbCacheManager(redis, redis, "group-" + i, configs, List.of(), 8,
                        new SingleFlightConfig(true, 4096, 1, 1000)));
            assertThrows(IllegalStateException.class, () -> new CacheDbCacheManager(redis, redis, "overflow",
                    configs, List.of(), 8, new SingleFlightConfig(true, 1, 1, 1000)));
        } finally { for (var manager : managers) manager.close(); }
        try (var after = new CacheDbCacheManager(redis, redis, "reused", configs, List.of(), 8,
                new SingleFlightConfig(true, 1, 1, 1000))) {
            assertNotNull(after.getCache("cache"));
        }
        verifyNoInteractions(redis);
    }

    private static final class PrefixCodec implements com.reactor.cachedb.core.codec.CacheValueCodec<String> {
        final String prefix;
        PrefixCodec(String prefix) { this.prefix = prefix; }
        public String encodeValue(String value) { return prefix + value; }
        public String decodeValue(String value) throws java.io.IOException {
            if (!value.startsWith(prefix)) throw new java.io.IOException("Incompatible codec schema");
            return value.substring(prefix.length());
        }
    }
    @Test void explicitCodecsRequireSharedIdentityNotMerelyTheSameImplementationClass() {
        var redis = mock(RedisClientOperations.class);
        var configs = Map.of("cache", CacheDbCacheContractTest.config(false, false));
        var codec = new PrefixCodec("one:");
        try (var first = new CacheDbCacheManager(redis, "codec-id", configs,
                List.of(new CacheDbCacheCodec<>("cache", codec)));
             var shared = new CacheDbCacheManager(redis, "codec-id", configs,
                List.of(new CacheDbCacheCodec<>("cache", codec)))) {
            assertSame(first.flights(), shared.flights());
            assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheManager(redis, "codec-id", configs,
                    List.of(new CacheDbCacheCodec<>("cache", new PrefixCodec("two:")))));
        }
        verifyNoInteractions(redis);
    }

    @Test void directConstructorsEnforceFlightKeyAndResultCharacterHardCapsBeforeIo() {
        var redis = mock(RedisClientOperations.class);
        var configs = Map.of("cache", CacheDbCacheContractTest.config(false, false));
        assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheManager(redis, redis, "caps", configs,
                List.of(), 8, new SingleFlightConfig(true, 4097, 8, 1000)));
        assertThrows(IllegalArgumentException.class, () -> new CacheDbCacheManager(redis, redis, "caps", configs,
                List.of(), 8, new SingleFlightConfig(true, 8, 8, 1000, 16 * 1024 * 1024 + 1)));
        try (var manager = new CacheDbCacheManager(redis, redis, "caps", configs, List.of(), 8,
                new SingleFlightConfig(true, 2, 1, 1000, 4));
             var first = manager.enterLoad("one");
             var follower = manager.enterLoad("one");
             var second = manager.enterLoad("two")) {
            assertThrows(IllegalStateException.class, () -> manager.enterLoad("one"));
            assertThrows(IllegalStateException.class, () -> manager.enterLoad("three"));
            assertThrows(IllegalArgumentException.class, () -> first.load("state", () -> "12345"));
        }
        verifyNoInteractions(redis);
    }

    @Test void delayedMissesUseCompletedHandoffButNewCallsAndSequentialCallsDoNotReuseIt() {
        var redis = mock(RedisClientOperations.class);
        var configs = Map.of("cache", CacheDbCacheContractTest.config(false, false));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var manager = new CacheDbCacheManager(redis, "handoff", configs, List.of());
             var first = manager.enterLoad("key");
             var delayed = manager.enterLoad("key")) {
            assertEquals("1first", first.load("old-state", () -> { calls.incrementAndGet(); return "1first"; }));
            first.close();
            assertEquals("1first", delayed.load("old-state", () -> { throw new AssertionError("Duplicate source"); }));
            try (var later = manager.enterLoad("key")) {
                assertEquals("1second", later.load("old-state", () -> { calls.incrementAndGet(); return "1second"; }));
            }
            delayed.close();
            try (var sequential = manager.enterLoad("key")) {
                assertEquals("1third", sequential.load("old-state", () -> { calls.incrementAndGet(); return "1third"; }));
            }
            assertEquals(3, calls.get());
        }
        verifyNoInteractions(redis);
    }

    @Test void completedHandoffBytesAreGloballyBoundedAndReleasedWhenLastRegistrantLeaves() {
        var redis = mock(RedisClientOperations.class);
        var configs = Map.of("cache", CacheDbCacheContractTest.config(false, false));
        var registrations = new java.util.ArrayList<CacheDbCacheFlights.Call>();
        try (var manager = new CacheDbCacheManager(redis, "byte-handoff", configs, List.of())) {
            for (int i = 0; i < 3; i++) {
                var owner = manager.enterLoad("key" + i);
                var delayed = manager.enterLoad("key" + i);
                registrations.add(owner); registrations.add(delayed);
                String value = "1" + "x".repeat(12 * 1024 * 1024);
                assertSame(value, owner.load("state" + i, () -> value));
                owner.close();
            }
            assertThrows(IllegalStateException.class, () -> registrations.get(5).load("state2",
                    () -> { throw new AssertionError("Budget overflow must not duplicate source work"); }));
        } finally { for (var registration : registrations) registration.close(); }
        try (var after = new CacheDbCacheManager(redis, "byte-handoff-after", configs, List.of());
             var owner = after.enterLoad("key");
             var delayed = after.enterLoad("key")) {
            assertEquals("1ok", owner.load("state", () -> "1ok"));
            assertEquals("1ok", delayed.load("state", () -> { throw new AssertionError("Budget was not reclaimed"); }));
        }
        verifyNoInteractions(redis);
    }
}
