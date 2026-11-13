package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;

class RedisStandalonePreferredCountTest {
    private static final String OWNER_WRONGTYPE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";

    @Test void ownedNativePrimaryBuildsEachWindowOnceWithoutLuaOrPayloadReads() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            for (int start = 0; start < 10_000; start += 512)
                pool.connection.reply(nativeReply(Math.min(512, 10_000 - start), true));
            int[] factories = {0};
            assertEquals(10_000, RedisStandaloneIndexSnapshot.indexCounts(pool, 10_000, start -> {
                factories[0]++;
                return keys(start, Math.min(512, 10_000 - start));
            }, "a").orElseThrow());
            assertEquals(20, factories[0]);
            assertEquals(1, pool.borrows);
            assertEquals(20, pool.connection.reads);
            assertEquals(20, Collections.frequency(pool.connection.names(), "MULTI"));
            assertEquals(20, Collections.frequency(pool.connection.names(), "EXEC"));
            assertEquals(40, Collections.frequency(pool.connection.names(), "EXISTS"));
            assertEquals(10_000, Collections.frequency(pool.connection.names(), "GETRANGE"));
            assertFalse(pool.connection.names().contains("EVAL"));
            assertEquals(10_080, pool.connection.names().size());
            assertTrue(pool.connection.requestedReplies.stream().allMatch(size -> size <= 516));
            assertEquals(1, pool.healthyReturns);
            assertEquals(0, pool.brokenReturns);
            assertTrue(pool.fallback.phases.isEmpty());
        }
    }

    @Test void ownedMixedLivenessDiscardsPartialCountsAndClosesLeaseBeforeLua() {
        for (int position : List.of(0, 1, 2)) for (boolean tombstone : List.of(false, true)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                for (int i = 0; i < 3; i++) {
                    var reply = nativeReply(512, true);
                    if (i == position) {
                        var values = new ArrayList<Object>((List<?>) reply.getLast());
                        values.set(tombstone ? 1 : 0, tombstone ? 1L : 511L);
                        values.set(2, new JedisDataException(OWNER_WRONGTYPE));
                        reply.set(reply.size() - 1, values);
                    }
                    pool.connection.reply(reply);
                }
                var evaluations = new ArrayList<Object>() {
                    @Override public Object get(int index) {
                        assertEquals(3, pool.connection.reads);
                        assertEquals(1, pool.brokenReturns);
                        assertEquals(0, pool.healthyReturns);
                        assertTrue(pool.connection.pending.isEmpty());
                        return super.get(index);
                    }
                };
                for (int i = 0; i < 3; i++)
                    evaluations.add(List.of(i == position ? 511L : 512L, List.of(), List.of()));
                pool.fallback.reply(evaluations);
                int[] factories = {0};
                assertEquals(1535, RedisStandaloneIndexSnapshot.indexCounts(pool, 1536, start -> {
                    factories[0]++;
                    return keys(start, 512);
                }, "a").orElseThrow());
                assertEquals(3, factories[0]);
                assertEquals(List.of("EVAL", "EVAL", "EVAL"), pool.fallback.names());
                assertFalse(pool.connection.names().contains("EVAL"));
                assertEquals(2, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertEquals(1, pool.healthyReturns);
            }
        }
    }

    @Test void uncertainNativeAclErrorsNeverBecomeCapabilityFallback() {
        var caused = new JedisAccessControlException("NOPERM No permissions to access a key",
                new IllegalStateException("uncertain origin"));
        var suppressed = new JedisAccessControlException("NOPERM No permissions to access a key");
        suppressed.addSuppressed(new IllegalStateException("uncertain state"));
        for (var failure : List.of(caused, suppressed, new JedisAccessControlException(
                "NOPERM this user has no permissions to run the 'getrange' command script: user_script:1"))) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var reply = nativeReply(512, true);
                reply.set(3, failure);
                reply.set(reply.size() - 1, new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
                pool.connection.reply(reply);
                pool.connection.reply(nativeReply(512, true));
                assertSame(failure, assertThrows(JedisAccessControlException.class,
                        () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a")));
                assertEquals(2, pool.connection.reads);
                assertEquals(1, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertEquals(0, pool.healthyReturns);
                assertFalse(pool.connection.names().contains("EVAL"));
                assertTrue(pool.fallback.phases.isEmpty());
            }
        }
    }

    @Test void unownedMultiDenialDrainsEveryWindowAndClosesLeaseBeforeEvalFallback() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            for (int i = 0; i < 2; i++) pool.connection.reply(multiDeniedReply(512, false));
            var evalReplies = new ArrayList<Object>() {
                @Override public Object get(int index) {
                    assertEquals(2, pool.connection.reads);
                    assertEquals(1, pool.brokenReturns);
                    assertEquals(0, pool.healthyReturns);
                    assertTrue(pool.connection.pending.isEmpty());
                    return super.get(index);
                }
            };
            evalReplies.add(List.of(512L, List.of(), List.of()));
            evalReplies.add(List.of(512L, List.of(), List.of()));
            pool.fallback.reply(evalReplies);
            int[] factories = {0};
            assertEquals(1024, RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> {
                factories[0]++;
                var owned = keys(start, 512);
                return new RedisClientOperations.IndexReadKeys(owned.entities(), owned.tombstones(),
                        owned.indexMetadata(), null);
            }, null).orElseThrow());
            assertEquals(2, factories[0]);
            assertEquals(List.of(4, 4), pool.connection.requestedReplies);
            assertFalse(pool.connection.names().contains("EVAL"));
            assertEquals(List.of("EVAL", "EVAL"), pool.fallback.names());
            assertEquals(2, pool.borrows);
            assertEquals(1, pool.brokenReturns);
            assertEquals(1, pool.healthyReturns);
        }
    }

    @Test void tombstoneKeyDenialDrainsAllWindowsBeforeLuaWithOrWithoutMultiPermission() {
        for (boolean owned : List.of(false, true)) for (boolean multiDenied : List.of(false, true)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var first = multiDenied ? multiDeniedReply(512, owned) : nativeReply(512, owned);
                first.set(2, new JedisAccessControlException("NOPERM No permissions to access a key"));
                if (multiDenied) {
                    first.set(1, 511L);
                    if (owned) first.set(3, new JedisDataException(OWNER_WRONGTYPE));
                } else first.set(first.size() - 1,
                        new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
                pool.connection.reply(first);
                pool.connection.reply(multiDenied ? multiDeniedReply(512, owned) : nativeReply(512, owned));
                var evaluations = new ArrayList<Object>() {
                    @Override public Object get(int index) {
                        assertEquals(2, pool.connection.reads);
                        assertEquals(1, pool.brokenReturns);
                        assertTrue(pool.connection.pending.isEmpty());
                        return super.get(index);
                    }
                };
                evaluations.add(List.of(0L, List.of(), List.of()));
                evaluations.add(List.of(3L, List.of(), List.of()));
                pool.fallback.reply(evaluations);
                assertEquals(3, RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> {
                    var window = keys(start, 512);
                    return owned ? window : new RedisClientOperations.IndexReadKeys(
                            window.entities(), window.tombstones(), window.indexMetadata(), null);
                }, owned ? "a" : null).orElseThrow());
                assertEquals(2, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertEquals(1, pool.healthyReturns);
                assertEquals(List.of("EVAL", "EVAL"), pool.fallback.names());
            }
        }
    }

    @Test void entityAndUnverifiedTombstoneAclErrorsRemainOriginalWithoutLuaFallback() {
        var caused = new JedisAccessControlException("NOPERM No permissions to access a key",
                new IllegalStateException("uncertain origin"));
        var suppressed = new JedisAccessControlException("NOPERM No permissions to access a key");
        suppressed.addSuppressed(new IllegalStateException("uncertain state"));
        var failures = List.of(caused, suppressed, new JedisAccessControlException("NOPERM ambiguous key denial"),
                new JedisAccessControlException("NOPERM User test has no permissions to run the 'exists' command"),
                new JedisAccessControlException("NOAUTH Authentication required."));
        for (boolean multiDenied : List.of(false, true)) for (int position : List.of(1, 2)) {
            var cases = position == 1 ? List.of(new JedisAccessControlException(
                    "NOPERM No permissions to access a key")) : failures;
            for (var failure : cases) {
                try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                    var reply = multiDenied ? multiDeniedReply(512, true) : nativeReply(512, true);
                    reply.set(position, failure);
                    if (!multiDenied) reply.set(reply.size() - 1,
                            new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
                    pool.connection.reply(reply);
                    pool.connection.reply(multiDenied ? multiDeniedReply(512, true) : nativeReply(512, true));
                    assertSame(failure, assertThrows(JedisAccessControlException.class, () ->
                            RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a")));
                    assertEquals(2, pool.connection.reads);
                    assertEquals(1, pool.borrows);
                    assertEquals(1, pool.brokenReturns);
                    assertTrue(pool.fallback.phases.isEmpty());
                }
            }
        }
    }

    @Test void multiDeniedMixedLivenessDefersOwnerWrongtypeToLuaAfterDrainingAndDiscarding() {
        for (boolean tombstone : List.of(false, true)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var mixed = multiDeniedReply(512, true);
                mixed.set(tombstone ? 2 : 1, tombstone ? 1L : 511L);
                mixed.set(3, new JedisDataException(OWNER_WRONGTYPE));
                pool.connection.reply(mixed);
                pool.connection.reply(multiDeniedReply(512, true));
                pool.fallback.reply(List.of(List.of(511L, List.of(), List.of()), List.of(512L, List.of(), List.of())));
                assertEquals(1023, RedisStandaloneIndexSnapshot.indexCounts(
                        pool, 1024, start -> keys(start, 512), "a").orElseThrow());
                assertEquals(2, pool.connection.reads);
                assertEquals(1, pool.brokenReturns);
                assertEquals(1, pool.healthyReturns);
                assertEquals(List.of("EVAL", "EVAL"), pool.fallback.names());
                assertTrue(pool.connection.pending.isEmpty());
            }
        }
    }

    @Test void multiDeniedMixedLivenessRetainsLuaLiveOwnerFailureAndWindowErrorOrder() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var first = multiDeniedReply(512, true);
            first.set(1, 511L);
            first.set(3, new JedisDataException(OWNER_WRONGTYPE));
            var second = multiDeniedReply(512, true);
            second.set(2, 1L);
            second.set(4, new JedisDataException(OWNER_WRONGTYPE));
            pool.connection.reply(first); pool.connection.reply(second);
            var original = new JedisDataException("WRONGTYPE first live owner from Lua");
            pool.fallback.reply(List.of(original, new JedisDataException("WRONGTYPE later Lua owner")));
            assertSame(original, assertThrows(JedisDataException.class,
                    () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a")));
            assertEquals(2, pool.connection.reads);
            assertEquals(1, pool.fallback.reads);
            assertEquals(2, pool.borrows);
            assertEquals(2, pool.brokenReturns);
            assertEquals(List.of("EVAL", "EVAL"), pool.fallback.names());
        }
    }

    @Test void multiDeniedMixedLivenessDoesNotHideUnverifiedAclOrNonTypeErrors() {
        var caused = new JedisDataException(OWNER_WRONGTYPE, new IllegalStateException("uncertain"));
        for (var failure : List.of(new JedisAccessControlException("NOAUTH Authentication required."),
                new JedisAccessControlException("NOPERM ambiguous owner failure"),
                new JedisDataException("ERR owner read failed"), caused)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var reply = multiDeniedReply(512, true);
                reply.set(1, 511L);
                reply.set(3, new JedisDataException(OWNER_WRONGTYPE));
                reply.set(4, failure);
                pool.connection.reply(reply); pool.connection.reply(multiDeniedReply(512, true));
                assertSame(failure, assertThrows(JedisDataException.class,
                        () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a")));
                assertEquals(2, pool.connection.reads);
                assertEquals(1, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertTrue(pool.fallback.phases.isEmpty());
            }
        }
    }

    @Test void earlierTombstoneKeyDenialKeepsLuaWindowOrderBeforeLaterLiveOwnerWrongtype() {
        for (boolean multiDenied : List.of(false, true)) for (boolean earlierLuaFails : List.of(false, true)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(tombstoneDeniedReply(multiDenied));
                var laterOwnerFailure = new JedisDataException(OWNER_WRONGTYPE);
                pool.connection.reply(liveOwnerFailureReply(multiDenied, laterOwnerFailure));
                var firstLuaFailure = new JedisDataException("ERR user_script: NOPERM first live tombstone");
                var laterLuaFailure = new JedisDataException("WRONGTYPE later Lua owner");
                var evaluations = new ArrayList<Object>() {
                    @Override public Object get(int index) {
                        assertEquals(2, pool.connection.reads);
                        assertEquals(1, pool.brokenReturns);
                        assertTrue(pool.connection.pending.isEmpty());
                        return super.get(index);
                    }
                };
                evaluations.add(earlierLuaFails ? firstLuaFailure : List.of(0L, List.of(), List.of()));
                evaluations.add(laterLuaFailure);
                pool.fallback.reply(evaluations);
                assertSame(earlierLuaFails ? firstLuaFailure : laterLuaFailure,
                        assertThrows(JedisDataException.class, () -> RedisStandaloneIndexSnapshot.indexCounts(
                                pool, 1024, start -> keys(start, 512), "a")));
                assertEquals(2, pool.connection.reads);
                assertEquals(1, pool.fallback.reads);
                assertEquals(2, pool.borrows);
                assertEquals(2, pool.brokenReturns);
                assertEquals(0, pool.healthyReturns);
                assertEquals(List.of("EVAL", "EVAL"), pool.fallback.names());
            }
        }
    }

    @Test void earlierRowRevalidationRetainsVerifiedLiveOwnerWrongtypeWhenEvalIsDenied() {
        for (boolean multiDenied : List.of(false, true)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(tombstoneDeniedReply(multiDenied));
                var original = new JedisDataException(OWNER_WRONGTYPE);
                pool.connection.reply(liveOwnerFailureReply(multiDenied, original));
                var denied = new JedisAccessControlException(
                        "NOPERM User test has no permissions to run the 'eval' command");
                pool.fallback.reply(List.of(denied, denied));
                assertSame(original, assertThrows(JedisDataException.class,
                        () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a")));
                assertEquals(2, pool.connection.reads);
                assertEquals(1, pool.fallback.reads);
                assertEquals(2, pool.borrows);
                assertEquals(2, pool.brokenReturns);
                assertEquals(List.of("EVAL", "EVAL"), pool.fallback.names());
            }
        }
    }

    @Test void earlierRowRevalidationDoesNotHideLaterUnverifiedOwnerErrors() {
        var caused = new JedisDataException(OWNER_WRONGTYPE, new IllegalStateException("uncertain"));
        var suppressed = new JedisDataException(OWNER_WRONGTYPE);
        suppressed.addSuppressed(new IllegalStateException("uncertain"));
        var failures = List.of(caused, suppressed, new JedisDataException("WRONGTYPE noncanonical owner failure"),
                new JedisAccessControlException(OWNER_WRONGTYPE),
                new JedisAccessControlException("NOAUTH Authentication required."),
                new JedisAccessControlException("NOPERM ambiguous owner failure"),
                new JedisDataException("ERR owner read failed"));
        for (boolean multiDenied : List.of(false, true)) for (var failure : failures) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(tombstoneDeniedReply(multiDenied));
                var later = liveOwnerFailureReply(multiDenied, new JedisDataException(OWNER_WRONGTYPE));
                if (multiDenied) later.set(4, failure);
                else {
                    var values = new ArrayList<Object>((List<?>) later.getLast());
                    values.set(3, failure);
                    later.set(later.size() - 1, values);
                }
                pool.connection.reply(later);
                assertSame(failure, assertThrows(JedisDataException.class,
                        () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a")));
                assertEquals(2, pool.connection.reads);
                assertEquals(1, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertTrue(pool.fallback.phases.isEmpty());
            }
        }
    }

    @Test void capabilityDenialAloneDoesNotDeferLaterLiveOwnerWrongtype() {
        for (boolean multiDenied : List.of(false, true)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var first = multiDenied ? multiDeniedReply(512, true) : nativeReply(512, true);
                if (!multiDenied) {
                    first.set(3, new JedisAccessControlException(
                            "NOPERM User test has no permissions to run the 'getrange' command"));
                    first.set(first.size() - 1,
                            new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
                }
                pool.connection.reply(first);
                var original = new JedisDataException(OWNER_WRONGTYPE);
                pool.connection.reply(liveOwnerFailureReply(multiDenied, original));
                assertSame(original, assertThrows(JedisDataException.class,
                        () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a")));
                assertEquals(2, pool.connection.reads);
                assertEquals(1, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertTrue(pool.fallback.phases.isEmpty());
            }
        }
    }

    @Test void earlierRowRevalidationStillRejectsLaterEntityErrorsAndBrokenTransactions() {
        for (boolean multiDenied : List.of(false, true)) for (int mode : List.of(0, 1, 2, 3)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(tombstoneDeniedReply(multiDenied));
                var later = liveOwnerFailureReply(multiDenied, new JedisDataException(OWNER_WRONGTYPE));
                var entityFailure = new JedisAccessControlException("NOPERM No permissions to access a key");
                if (mode == 0) {
                    later.set(1, entityFailure);
                    if (!multiDenied) later.set(later.size() - 1,
                            new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
                } else if (mode == 1) {
                    later.set(later.size() - 1, new JedisDataException("ERR unverified transaction tail"));
                } else if (mode == 2) {
                    later.removeLast();
                } else pool.connection.failureAtRead = 2;
                pool.connection.reply(later);
                var failure = assertThrows(RuntimeException.class,
                        () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a"));
                if (mode == 0) assertSame(entityFailure, failure);
                else if (mode == 1) assertEquals("ERR unverified transaction tail", failure.getMessage());
                else if (mode == 2) assertInstanceOf(IllegalStateException.class, failure);
                else assertInstanceOf(redis.clients.jedis.exceptions.JedisConnectionException.class, failure);
                assertEquals(2, pool.connection.reads);
                assertEquals(1, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertTrue(pool.fallback.phases.isEmpty());
            }
        }
    }

    @Test void multiDeniedCompleteLivenessStillPropagatesActiveOwnerWrongtype() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var original = new JedisDataException(OWNER_WRONGTYPE);
            var reply = multiDeniedReply(512, true); reply.set(3, original);
            pool.connection.reply(reply); pool.connection.reply(multiDeniedReply(512, true));
            assertSame(original, assertThrows(JedisDataException.class,
                    () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a")));
            assertEquals(2, pool.connection.reads);
            assertEquals(1, pool.brokenReturns);
            assertTrue(pool.fallback.phases.isEmpty());
        }
    }

    @Test void sameWindowOwnerKeyDenialKeepsLuaRowOrderAfterCompleteDeniedProof() {
        for (boolean legacyReadDenied : List.of(false, true)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var first = multiDeniedReply(512, true);
                first.set(3, new JedisAccessControlException("NOPERM No permissions to access a key"));
                first.set(4, new JedisDataException(OWNER_WRONGTYPE));
                pool.connection.reply(first);
                pool.connection.reply(multiDeniedReply(512, true));
                var expected = legacyReadDenied
                        ? new JedisDataException("ERR user_script: NOPERM first owner legacy read")
                        : new JedisDataException(OWNER_WRONGTYPE);
                var evaluations = new ArrayList<Object>() {
                    @Override public Object get(int index) {
                        assertEquals(2, pool.connection.reads);
                        assertEquals(1, pool.brokenReturns);
                        assertTrue(pool.connection.pending.isEmpty());
                        return super.get(index);
                    }
                };
                evaluations.add(expected);
                evaluations.add(List.of(512L, List.of(), List.of()));
                pool.fallback.reply(evaluations);
                assertSame(expected, assertThrows(JedisDataException.class, () ->
                        RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> keys(start, 512), "a")));
                assertEquals(2, pool.connection.reads);
                assertEquals(1, pool.fallback.reads);
                assertEquals(2, pool.borrows);
                assertEquals(2, pool.brokenReturns);
                assertEquals(List.of("EVAL", "EVAL"), pool.fallback.names());
            }
        }
    }

    private static RedisClientOperations.IndexReadKeys keys(int start, int size) {
        return new RedisClientOperations.IndexReadKeys(names("entity:", start, size), names("tomb:", start, size),
                Collections.nCopies(size, null), names("owner:", start, size));
    }

    private static List<String> names(String prefix, int start, int size) {
        return IntStream.range(start, start + size).mapToObj(i -> prefix + i).toList();
    }

    private static ArrayList<Object> nativeReply(int size, boolean owned) {
        var values = new ArrayList<Object>();
        values.add((long) size); values.add(0L);
        if (owned) for (int i = 0; i < size; i++) values.add("a".getBytes(StandardCharsets.UTF_8));
        var reply = new ArrayList<Object>();
        reply.add("OK".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < values.size(); i++) reply.add("QUEUED".getBytes(StandardCharsets.UTF_8));
        reply.add(values);
        return reply;
    }

    private static ArrayList<Object> multiDeniedReply(int size, boolean owned) {
        var reply = nativeReply(size, owned);
        reply.set(0, new JedisAccessControlException("NOPERM User test has no permissions to run the 'multi' command"));
        reply.set(1, (long) size); reply.set(2, 0L);
        for (int i = 3; i < reply.size() - 1; i++) reply.set(i, "a".getBytes(StandardCharsets.UTF_8));
        reply.set(reply.size() - 1, new JedisDataException("ERR EXEC without MULTI"));
        return reply;
    }

    private static ArrayList<Object> tombstoneDeniedReply(boolean multiDenied) {
        var reply = multiDenied ? multiDeniedReply(512, true) : nativeReply(512, true);
        reply.set(2, new JedisAccessControlException("NOPERM No permissions to access a key"));
        if (!multiDenied) reply.set(reply.size() - 1,
                new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
        return reply;
    }

    private static ArrayList<Object> liveOwnerFailureReply(boolean multiDenied, JedisDataException failure) {
        var reply = multiDenied ? multiDeniedReply(512, true) : nativeReply(512, true);
        if (multiDenied) reply.set(3, failure);
        else {
            var values = new ArrayList<Object>((List<?>) reply.getLast());
            values.set(2, failure);
            reply.set(reply.size() - 1, values);
        }
        return reply;
    }
}
