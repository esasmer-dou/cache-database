package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.SafeEncoder;
import redis.clients.jedis.util.Pool;

class RedisBinaryCountKeysTest {
    @Test void asciiKeysMatchCanonicalNativeAndEvalBytesAcrossFullAndTailWindows() {
        var ids = new ArrayList<String>();
        ids.add(""); ids.add(null);
        for (int i = 0; i < 525; i++) ids.add(String.valueOf((char) (i % 128)) + ":id:" + i);
        assertCanonicalKeyWindows(ids);
    }

    @Test void unicodeAfterLongAsciiPrefixesRetainsCanonicalReplacementBytes() {
        var ids = new ArrayList<>(Collections.nCopies(512, "ascii"));
        for (String tail : List.of("\u0080", "\u00e9", "\u4e00", "\ud83d\ude80", "\ud800", "\udc00", "\ud800x\udc00"))
            ids.add("a".repeat(4096) + tail);
        assertCanonicalKeyWindows(ids);
    }

    @Test void evalArgumentsMatchCanonicalLuaAndKeysForEveryBuiltInLayout() {
        var unusual = Arrays.asList("", "a:b", "{other}", "a\u0000b", "\u00c7\u0131\u011f",
                "\ud83d\ude80", "\ud800", "\udc00", null);
        var ids = new ArrayList<String>();
        for (int i = 0; i < 527; i++) ids.add(unusual.get(i % unusual.size()));
        for (var strategy : List.of(new RedisKeyStrategy(),
                new RedisKeyStrategy("p\ud800", "e\udc00", "page", "v", "t", "hot", "ix", "compact"),
                new ClusterHashTagKeyStrategy("prefix", "scope"), new ClusterHashTagKeyStrategy("prefix"))) {
            for (String tenant : Arrays.asList(null, "\u00c7\u0131\u011f")) {
                var prepared = new RedisBinaryCountKeys(strategy, "ns:projection:p\ud800", ids, tenant).prepare();
                for (int start = 0; start < ids.size(); start += 512) {
                    int size = Math.min(512, ids.size() - start);
                    assertWireEquals(canonical(strategy, "ns:projection:p\ud800", ids.subList(start, start + size), tenant),
                            prepared.evalCommand(start, size));
                }
            }
        }
    }

    @Test void unownedNativeCommandsKeepTheExactMultiExistsExistsExecWireLayout() {
        for (var strategy : List.of(new RedisKeyStrategy(), new ClusterHashTagKeyStrategy("p", "scope"))) {
            var ids = new ArrayList<String>();
            for (int i = 0; i < 527; i++) ids.add(i % 2 == 0 ? "\ud800:\u00c7" : null);
            var prepared = new RedisBinaryCountKeys(strategy, "ns", ids, null).prepare();
            for (int start : List.of(0, 512)) {
                int size = Math.min(512, ids.size() - start);
                var batch = ids.subList(start, start + size);
                var expected = List.of(new CommandArguments(Command.MULTI),
                        new CommandArguments(Command.EXISTS).addObjects(batch.stream().map(id -> strategy.entityKey("ns", id)).toList()),
                        new CommandArguments(Command.EXISTS).addObjects(batch.stream().map(id -> strategy.tombstoneKey("ns", id)).toList()),
                        new CommandArguments(Command.EXEC));
                var actual = prepared.nativeCommands(start, size);
                for (int i = 0; i < expected.size(); i++) assertWireEquals(expected.get(i), actual.commandAt(i));
            }
        }
    }

    @Test void customStrategiesOperationsAndNonUtf8CharsetsAreNotEligible() {
        assertEquals(StandardCharsets.UTF_8.equals(SafeEncoder.DEFAULT_CHARSET),
                RedisBinaryCountKeys.supports(new RedisKeyStrategy()));
        var custom = new RedisKeyStrategy() {
            @Override public String entityKey(String namespace, Object id) { throw new AssertionError("No custom key call"); }
        };
        assertFalse(RedisBinaryCountKeys.supports(custom));
        assertThrows(IllegalArgumentException.class, () -> new RedisBinaryCountKeys(custom, "ns", List.of("id"), null));
        assertFalse(RedisBinaryCountKeys.supports((RedisKeyStrategy) null));
        for (boolean clustered : List.of(false, true)) {
            var operations = new RedisClientOperations() {
                @Override public UnifiedJedis commands() { throw new AssertionError("No custom transport call"); }
                @Override public boolean clustered() { return clustered; }
            };
            assertFalse(RedisBinaryCountKeys.supports(operations, new RedisKeyStrategy()));
        }
        assertFalse(RedisBinaryCountKeys.supports(null, new RedisKeyStrategy()));
    }

    @Test void identitiesAndAllMutableCommandBytesRemainRequestLocal() {
        var ids = new ArrayList<>(Collections.nCopies(1024, "same"));
        var strategy = new RedisKeyStrategy();
        var keys = new RedisBinaryCountKeys(strategy, "ns", ids, "a");
        ids.set(0, "changed"); ids.clear();
        var prepared = keys.prepare();
        var commands = new ArrayList<>(List.of(prepared.evalCommand(0, 512), prepared.evalCommand(512, 512)));
        prepared.independentScripts(commands);
        var first = arguments(commands.getFirst());
        var second = arguments(commands.getLast());
        byte[] script = second.get(1).clone(), entity = second.get(3).clone();
        assertNotSame(first.get(1), second.get(1));
        assertNotSame(first.get(3), second.get(3));
        assertNotSame(first.get(3), first.get(4));
        first.get(1)[0] ^= 1; first.get(3)[0] ^= 1;
        assertArrayEquals(script, second.get(1)); assertArrayEquals(entity, second.get(3));
        assertWireEquals(canonical(strategy, "ns", Collections.nCopies(512, "same"), "a"), prepared.evalCommand(0, 512));
        var another = new RedisBinaryCountKeys(strategy, "ns", Collections.nCopies(1024, "same"), "a").prepare();
        assertNotSame(arguments(prepared.evalCommand(0, 512)).get(1), arguments(another.evalCommand(0, 512)).get(1));
        assertEquals("same", keys.window(0).entities().getFirst().substring(strategy.entityKey("ns", "").length()));
    }

    @Test void exactUtf16KeyAndConservativeWindowByteLimitsMatchCanonicalAdmission() {
        var strategy = new RedisKeyStrategy();
        for (String tenant : Arrays.asList(null, "\u00c7")) {
            int prefixSum = strategy.entityKey("ns", "").length() + strategy.tombstoneKey("ns", "").length();
            int maxPrefix = Math.max(strategy.entityKey("ns", "").length(), strategy.tombstoneKey("ns", "").length());
            if (tenant != null) {
                int owner = strategy.versionKey("ns", "").length() + ":tenant".length();
                prefixSum += owner; maxPrefix = Math.max(maxPrefix, owner);
            }
            String allowed = "\ud83d\ude80".repeat((16_384 - maxPrefix) / 2) + "x".repeat((16_384 - maxPrefix) % 2);
            var atLimit = List.of(allowed);
            assertWireEquals(canonical(strategy, "ns", atLimit, tenant),
                    new RedisBinaryCountKeys(strategy, "ns", atLimit, tenant).prepare().evalCommand(0, 1));
            var overLimit = List.of(allowed + "x");
            assertThrows(IllegalArgumentException.class, () -> canonical(strategy, "ns", overLimit, tenant));
            assertThrows(IllegalArgumentException.class, () -> new RedisBinaryCountKeys(strategy, "ns", overLimit, tenant).prepare());
            int families = tenant == null ? 2 : 3;
            int length = (int) ((RedisStandaloneIndexSnapshot.MAX_BYTES / 3 - 8192
                    - (tenant == null ? 0 : tenant.length()) - 512L * prefixSum) / (512L * families));
            for (boolean over : List.of(false, true)) {
                var window = Collections.nCopies(512, "x".repeat(length + (over ? 1 : 0)));
                if (over) {
                    assertThrows(IllegalArgumentException.class, () -> canonical(strategy, "ns", window, tenant));
                    assertThrows(IllegalArgumentException.class, () -> new RedisBinaryCountKeys(strategy, "ns", window, tenant).prepare());
                } else assertWireEquals(canonical(strategy, "ns", window, tenant),
                        new RedisBinaryCountKeys(strategy, "ns", window, tenant).prepare().evalCommand(0, 512));
            }
        }
    }

    @Test void phaseWireBudgetHasTheSameExactBoundaryAsStringCommands() {
        var ids = Collections.nCopies(512, "\u4e00".repeat(128));
        var binary = new RedisBinaryCountKeys(new RedisKeyStrategy(), "ns", ids, "a").prepare().evalCommand(0, 512);
        var stock = canonical(new RedisKeyStrategy(), "ns", ids, "a");
        var expected = new RedisStandaloneIndexSnapshot.Phase();
        var actual = new RedisStandaloneIndexSnapshot.Phase();
        for (;;) {
            try { expected.add(stock); }
            catch (RuntimeException limit) {
                assertEquals(limit.getClass(), assertThrows(RuntimeException.class, () -> actual.add(binary)).getClass());
                break;
            }
            actual.add(binary);
        }
        assertEquals(expected.size(), actual.size());
        assertTrue(actual.size() < RedisStandaloneIndexSnapshot.MAX_COMMANDS);
    }

    @Test void invalidTenantWindowAndNativeOwnershipFailBeforeAnyCommands() {
        for (String tenant : List.of("", " ", "x".repeat(4097)))
            assertThrows(IllegalArgumentException.class, () -> new RedisBinaryCountKeys(new RedisKeyStrategy(), "ns", List.of("id"), tenant));
        var keys = binaryKeys(1024, "a");
        var prepared = keys.prepare();
        assertThrows(IllegalArgumentException.class, () -> keys.requireTenant("b"));
        assertThrows(IllegalArgumentException.class, () -> prepared.evalCommand(-1, 512));
        assertThrows(IllegalArgumentException.class, () -> prepared.evalCommand(1, 512));
        assertThrows(IllegalArgumentException.class, () -> prepared.evalCommand(0, 511));
        assertThrows(IllegalArgumentException.class, () -> prepared.evalCommand(1024, 0));
        assertEquals(516, prepared.nativeCommands(0, 512).size());
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            assertThrows(IllegalArgumentException.class, () -> RedisStandaloneIndexSnapshot.indexCounts(pool, keys, "b"));
            assertEquals(0, pool.borrows);
        }
    }

    @Test void ownedNativePrimaryKeepsPresentZeroAndOneFullyDrainedLeaseWithoutEval() {
        for (long count : List.of(0L, 512L)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(exec(512, count == 0 ? "b" : "a"));
                pool.connection.reply(exec(512, count == 0 ? "b" : "a"));
                assertEquals(count * 2, RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1024, "a"), "a").orElseThrow());
                assertFalse(pool.connection.names().contains("EVAL"));
                assertEquals(2, Collections.frequency(pool.connection.names(), "EXEC"));
                assertEquals(1024, Collections.frequency(pool.connection.names(), "GETRANGE"));
                assertEquals(2, pool.connection.reads); assertEquals(1, pool.borrows);
                assertEquals(1, pool.healthyReturns); assertEquals(0, pool.brokenReturns);
            }
        }
    }

    @Test void ownedLazyBatchesKeepFullTenantAndIndependentRetainedCustomConnectionCommands() {
        int rows = 1025;
        String tenant = "x".repeat(4094) + "\u00c7\u0131";
        for (var strategy : List.of(new RedisKeyStrategy(), new ClusterHashTagKeyStrategy("p", "scope"))) {
            var ids = new ArrayList<String>();
            for (int i = 0; i < rows; i++) ids.add("id:" + i + ":\ud800:\u00c7");
            var frozenIds = List.copyOf(ids);
            var keys = new RedisBinaryCountKeys(strategy, "ns", ids, tenant);
            ids.set(0, "changed"); ids.clear();
            var retained = new ArrayList<CommandArguments>();
            int[] reads = {0}, healthy = {0};
            Connection connection = new Connection() {
                @Override public boolean isConnected() { return true; }
                @Override public void sendCommand(CommandArguments command) { retained.add(command); }
                @Override public List<Object> getMany(int count) {
                    assertEquals(rows + 12, retained.size(), "All windows must be sent before reading");
                    int start = reads[0]++ * 512;
                    int size = Math.min(512, rows - start);
                    var reply = exec(size, tenant);
                    assertEquals(count, reply.size());
                    return reply;
                }
            };
            var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false);
            try (var pool = new Pool<Connection>(new BasePooledObjectFactory<Connection>() {
                @Override public Connection create() { throw new AssertionError("No socket expected"); }
                @Override public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
            }, config) {
                @Override public Connection borrowObject(Duration wait) { return connection; }
                @Override public void returnResource(Connection value) { assertSame(connection, value); healthy[0]++; }
                @Override public void returnBrokenResource(Connection value) { fail("Healthy custom connection discarded"); }
            }) {
                assertEquals(rows, RedisStandaloneIndexSnapshot.indexCounts(pool, keys, tenant).orElseThrow());
                assertEquals(3, reads[0]); assertEquals(1, healthy[0]);
                var distinctOwners = Collections.newSetFromMap(new IdentityHashMap<CommandArguments, Boolean>());
                int offset = 0;
                for (int start = 0; start < rows; start += 512) {
                    int end = Math.min(rows, start + 512);
                    var window = frozenIds.subList(start, end);
                    assertWireEquals(new CommandArguments(Command.MULTI), retained.get(offset++));
                    assertWireEquals(new CommandArguments(Command.EXISTS).addObjects(window.stream()
                            .map(id -> strategy.entityKey("ns", id)).toList()), retained.get(offset++));
                    assertWireEquals(new CommandArguments(Command.EXISTS).addObjects(window.stream()
                            .map(id -> strategy.tombstoneKey("ns", id)).toList()), retained.get(offset++));
                    for (String id : window) {
                        var command = retained.get(offset++);
                        assertTrue(distinctOwners.add(command));
                        assertWireEquals(new CommandArguments(Command.GETRANGE)
                                .add(strategy.versionKey("ns", id) + ":tenant").add(0).add(bytes(tenant).length), command);
                        var arguments = command.iterator(); arguments.next();
                        assertInstanceOf(redis.clients.jedis.args.RawableFactory.RawString.class, arguments.next());
                    }
                    assertWireEquals(new CommandArguments(Command.EXEC), retained.get(offset++));
                }
                assertEquals(retained.size(), offset);
                assertTrue(retained.stream().noneMatch(command -> command.getCommand() == Command.EVAL));
            }
        }
    }

    @Test void ownedBinaryEarlierTombstoneDeclineKeepsLuaOrderAndRetainsWrongtypeWhenEvalIsDenied() {
        for (boolean multiDenied : List.of(false, true)) for (int outcome : List.of(0, 1, 2)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var first = new ArrayList<>(multiDenied ? deniedNative(512, "a") : exec(512, "a"));
                first.set(2, new JedisAccessControlException("NOPERM No permissions to access a key"));
                if (!multiDenied) first.set(first.size() - 1,
                        new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
                pool.connection.reply(first);
                var second = new ArrayList<>(multiDenied ? deniedNative(512, "a") : exec(512, "a"));
                var ownerFailure = new JedisDataException(
                        "WRONGTYPE Operation against a key holding the wrong kind of value");
                if (multiDenied) second.set(3, ownerFailure);
                else {
                    var values = new ArrayList<Object>((List<?>) second.getLast()); values.set(2, ownerFailure);
                    second.set(second.size() - 1, values);
                }
                pool.connection.reply(second);
                var firstLuaFailure = new JedisDataException("ERR user_script: NOPERM first live tombstone");
                var laterLuaFailure = new JedisDataException("WRONGTYPE later Lua owner");
                pool.fallback.reply(outcome == 2 ? List.of(denied("eval"), denied("eval"))
                        : List.of(outcome == 0 ? firstLuaFailure : result(0), laterLuaFailure));
                assertSame(outcome == 0 ? firstLuaFailure : outcome == 1 ? laterLuaFailure : ownerFailure,
                        assertThrows(JedisDataException.class, () ->
                                RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1024, "a"), "a")));
                assertEquals(2, pool.connection.reads); assertEquals(1, pool.fallback.reads);
                assertEquals(2, pool.borrows); assertEquals(2, pool.brokenReturns);
                assertEquals(List.of("EVAL", "EVAL"), pool.fallback.names());
            }
        }
    }

    @Test void ownedEvalFallbackKeepsPresentZeroAfterNativeDecline() {
        for (long count : List.of(0L, 512L)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(deniedNative(512, "a")); pool.connection.reply(deniedNative(512, "a"));
                pool.fallback.reply(List.of(result(count), result(count)));
                assertEquals(count * 2, RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1024, "a"), "a").orElseThrow());
                assertEquals(2, pool.connection.reads); assertEquals(1, pool.fallback.reads);
                assertEquals(List.of("EVAL", "EVAL"), pool.fallback.names());
                assertEquals(2, pool.borrows); assertEquals(1, pool.brokenReturns); assertEquals(1, pool.healthyReturns);
            }
        }
    }

    @Test void firstActualEvalDenialSurvivesNativePrimaryDecline() {
        for (boolean keyDenial : List.of(false, true)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var first = keyDenial ? new JedisAccessControlException("NOPERM No permissions to access a key") : denied("eval");
                var second = denied("eval");
                pool.connection.reply(deniedNative(512, "a")); pool.connection.reply(deniedNative(512, "a"));
                pool.fallback.reply(List.of(first, second));
                assertSame(first, assertThrows(JedisAccessControlException.class,
                        () -> RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1024, "a"), "a")));
                assertEquals(2, pool.connection.reads); assertEquals(1, pool.fallback.reads);
                assertEquals(2, pool.borrows); assertEquals(2, pool.brokenReturns);
            }
        }
    }

    @Test void ownedNativePrimaryDoesNotTreatALongerOwnerPrefixAsTheTenant() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(exec(512, "ax")); pool.connection.reply(exec(512, "ax"));
            assertEquals(0, RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1024, "a"), "a").orElseThrow());
            assertEquals(2, pool.connection.reads); assertEquals(0, pool.fallback.reads);
            assertFalse(pool.connection.names().contains("EVAL"));
            assertEquals(1, pool.borrows); assertEquals(0, pool.brokenReturns); assertEquals(1, pool.healthyReturns);
        }
    }

    @Test void evalDenialCannotHidePoisonOrMalformedCountsInAnyWindow() {
        for (Object poison : List.of(new JedisDataException("WRONGTYPE"), result(-1), result(513),
                List.of(0L, List.of(), List.of(0L)))) {
            for (int position : List.of(0, 1, 2)) {
                try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                    var replies = new ArrayList<Object>(List.of(denied("eval"), denied("eval"), denied("eval")));
                    replies.set(position, poison);
                    for (int i = 0; i < 3; i++) pool.connection.reply(deniedNative(512, "a"));
                    pool.fallback.reply(replies);
                    var failure = assertThrows(RuntimeException.class,
                            () -> RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1536, "a"), "a"));
                    if (poison instanceof JedisDataException) assertSame(poison, failure);
                    else assertInstanceOf(IllegalStateException.class, failure);
                    assertEquals(3, pool.connection.reads); assertEquals(1, pool.fallback.reads);
                    assertEquals(2, pool.borrows);
                    assertEquals(2, pool.brokenReturns); assertEquals(0, pool.healthyReturns);
                }
            }
        }
    }

    @Test void unownedIsNativeFirstAndMixedLivenessFallsBackOnlyAfterAllWindowsDrain() {
        for (int mixed : List.of(-1, 0, 1, 2)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                for (int i = 0; i < 3; i++) pool.connection.reply(unownedExec(i == mixed ? 511 : 512, 0));
                if (mixed >= 0) pool.fallback.reply(List.of(result(mixed == 0 ? 511 : 512),
                        result(mixed == 1 ? 511 : 512), result(mixed == 2 ? 511 : 512)));
                assertEquals(mixed < 0 ? 1536 : 1535,
                        RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1536, null), null).orElseThrow());
                assertEquals(3, pool.connection.reads);
                var commands = Collections.nCopies(3, List.of("MULTI", "EXISTS", "EXISTS", "EXEC"))
                        .stream().flatMap(List::stream).toList();
                assertEquals(commands, pool.connection.names());
                // The fake captures pending sends on each read, not on each transaction boundary.
                assertEquals(List.of(commands, List.of(), List.of()),
                        pool.connection.phases.stream().map(phase -> phase.stream().map(command -> command.getFirst()).toList()).toList());
                assertEquals(List.of(4, 4, 4), pool.connection.requestedReplies);
                assertEquals(mixed < 0 ? 1 : 2, pool.borrows);
                if (mixed >= 0) assertEquals(List.of("EVAL", "EVAL", "EVAL"), pool.fallback.names());
            }
        }
    }

    @Test void aNativeRejectionCannotMaskAnotherWindowsErrorOrBroadenKeyAccess() {
        for (boolean owned : List.of(false, true)) {
            for (Object poison : List.of(new JedisDataException("WRONGTYPE"),
                    new JedisAccessControlException("NOPERM No permissions to access a key"))) {
                try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                    var nativeConnection = pool.connection;
                    nativeConnection.reply(deniedNative(512, owned ? "a" : null));
                    var poisoned = owned ? exec(512, "a") : unownedExec(512, 0);
                    var values = new ArrayList<Object>((List<?>) poisoned.getLast()); values.set(0, poison);
                    poisoned.set(poisoned.size() - 1, values); nativeConnection.reply(poisoned);
                    assertSame(poison, assertThrows(JedisDataException.class,
                            () -> RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1024, owned ? "a" : null), owned ? "a" : null)));
                    assertEquals(2, nativeConnection.reads); assertEquals(1, pool.borrows);
                    assertFalse(nativeConnection.names().contains("EVAL"));
                }
            }
        }
    }

    @Test void phaseBudgetAndUnsupportedSizesDeclineBeforeBorrow() {
        for (String tenant : Arrays.asList(null, "a")) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                int size = tenant == null ? 10_000 : 2048;
                var ids = Collections.nCopies(size, "x".repeat(tenant == null ? 900 : 2800));
                var keys = new RedisBinaryCountKeys(new RedisKeyStrategy(), "ns", ids, tenant);
                assertTrue(RedisStandaloneIndexSnapshot.indexCounts(pool, keys, tenant).isEmpty());
                assertTrue(RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(512, tenant), tenant).isEmpty());
                assertTrue(RedisStandaloneIndexSnapshot.indexCounts(pool,
                        binaryKeys(512 * RedisStandaloneIndexSnapshot.MAX_COMMANDS + 1, tenant), tenant).isEmpty());
                assertEquals(0, pool.borrows); assertEquals(0, pool.connection.sends);
            }
        }
    }

    @Test void allWindowsArePreflightedBeforeBorrowAndCancellationIsNotCapabilityAbsence() {
        var ids = new ArrayList<>(Collections.nCopies(1024, "id")); ids.set(1023, "x".repeat(16_384));
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            assertThrows(IllegalArgumentException.class, () -> RedisStandaloneIndexSnapshot.indexCounts(pool,
                    new RedisBinaryCountKeys(new RedisKeyStrategy(), "ns", ids, "a"), "a"));
            assertEquals(0, pool.borrows);
            Thread.currentThread().interrupt();
            try { assertThrows(CancellationException.class, () -> RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1024, "a"), "a")); }
            finally { Thread.interrupted(); }
            assertEquals(0, pool.borrows);
        }
    }

    @Test void transportCancellationAndReturnFailuresNeverSelectAnotherAlgorithm() {
        for (String tenant : Arrays.asList(null, "a")) {
            for (int mode = 0; mode < 5; mode++) {
                try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                    if (tenant == null) {
                        pool.connection.reply(unownedExec(512, 0)); pool.connection.reply(unownedExec(512, 0));
                    } else {
                        pool.connection.reply(exec(512, "a")); pool.connection.reply(exec(512, "a"));
                    }
                    pool.connection.failureAtSend = mode == 0 ? 2 : -1;
                    pool.connection.failureAtRead = mode == 1 ? 1 : -1;
                    pool.connection.interruptAtSend = mode == 2 ? 2 : -1;
                    pool.connection.interruptAtRead = mode == 3 ? 1 : -1;
                    pool.failReturn = mode == 4;
                    try {
                        var failure = assertThrows(RuntimeException.class,
                                () -> RedisStandaloneIndexSnapshot.indexCounts(pool, binaryKeys(1024, tenant), tenant));
                        if (mode == 2 || mode == 3) assertInstanceOf(CancellationException.class, failure);
                        else assertInstanceOf(JedisConnectionException.class, failure);
                        assertEquals(1, pool.borrows);
                        if (mode != 4) assertEquals(1, pool.brokenReturns);
                    } finally { Thread.interrupted(); }
                }
            }
        }
    }

    private static void assertCanonicalKeyWindows(List<String> ids) {
        String namespace = "ns:\u4e00:\ud800";
        for (var strategy : List.of(new RedisKeyStrategy(), new ClusterHashTagKeyStrategy("p", "scope"))) {
            for (String tenant : Arrays.asList(null, "\u00c7\u0131\u011f")) {
                var prepared = new RedisBinaryCountKeys(strategy, namespace, ids, tenant).prepare();
                for (int start = 0; start < ids.size(); start += 512) {
                    int size = Math.min(512, ids.size() - start);
                    var window = ids.subList(start, start + size);
                    assertWireEquals(canonical(strategy, namespace, window, tenant), prepared.evalCommand(start, size));
                    var expected = new ArrayList<CommandArguments>();
                    expected.add(new CommandArguments(Command.MULTI));
                    expected.add(new CommandArguments(Command.EXISTS).addObjects(window.stream()
                            .map(id -> strategy.entityKey(namespace, id)).toList()));
                    expected.add(new CommandArguments(Command.EXISTS).addObjects(window.stream()
                            .map(id -> strategy.tombstoneKey(namespace, id)).toList()));
                    if (tenant != null) for (String id : window)
                        expected.add(new CommandArguments(Command.GETRANGE)
                                .add(strategy.versionKey(namespace, id) + ":tenant").add(0).add(bytes(tenant).length));
                    expected.add(new CommandArguments(Command.EXEC));
                    var actual = prepared.nativeCommands(start, size);
                    assertEquals(expected.size(), actual.size());
                    for (int i = 0; i < expected.size(); i++) assertWireEquals(expected.get(i), actual.commandAt(i));
                }
            }
        }
    }

    private static RedisBinaryCountKeys binaryKeys(int size, String tenant) {
        var ids = java.util.stream.IntStream.range(0, size).mapToObj(Integer::toString).toList();
        return new RedisBinaryCountKeys(new RedisKeyStrategy(), "ns", ids, tenant);
    }

    private static CommandArguments canonical(RedisKeyStrategy strategy, String namespace, List<String> ids, String tenant) {
        var command = RedisClientOperations.indexAggregateCommand(
                ids.stream().map(id -> strategy.entityKey(namespace, id)).toList(),
                ids.stream().map(id -> strategy.tombstoneKey(namespace, id)).toList(), Collections.nCopies(ids.size(), null),
                tenant == null ? null : ids.stream().map(id -> strategy.versionKey(namespace, id) + ":tenant").toList(), tenant, null, true);
        return RedisStandaloneIndexSnapshot.evalCommand(command.script(), command.keys(), command.arguments());
    }

    private static List<byte[]> arguments(CommandArguments command) {
        var arguments = new ArrayList<byte[]>();
        for (var argument : command) arguments.add(argument.getRaw());
        return arguments;
    }

    private static void assertWireEquals(CommandArguments expected, CommandArguments actual) {
        assertEquals(expected.getCommand(), actual.getCommand()); assertEquals(expected.size(), actual.size());
        var left = arguments(expected); var right = arguments(actual);
        for (int i = 0; i < left.size(); i++) assertArrayEquals(left.get(i), right.get(i), "argument " + i);
        assertTrue(actual.getKeys().isEmpty());
    }

    private static List<Object> result(long count) { return List.of(count, List.of(), List.of()); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JedisAccessControlException denied(String command) {
        return new JedisAccessControlException("NOPERM User test has no permissions to run the '" + command + "' command");
    }

    private static ArrayList<Object> unownedExec(long live, long deleted) {
        return new ArrayList<>(List.of(bytes("OK"), bytes("QUEUED"), bytes("QUEUED"), List.of(live, deleted)));
    }

    private static ArrayList<Object> exec(int size, String owner) {
        var values = new ArrayList<Object>(); values.add((long) size); values.add(0L);
        for (int i = 0; i < size; i++) values.add(bytes(owner));
        var reply = new ArrayList<Object>(); reply.add(bytes("OK"));
        for (int i = 0; i < values.size(); i++) reply.add(bytes("QUEUED"));
        reply.add(values); return reply;
    }

    private static List<Object> deniedNative(int size, String owner) {
        var reply = owner == null ? unownedExec(size, 0) : exec(size, owner);
        reply.set(0, denied("multi")); reply.set(1, (long) size); reply.set(2, 0L);
        if (owner != null) for (int i = 0; i < size; i++) reply.set(i + 3, bytes(owner));
        reply.set(reply.size() - 1, new JedisDataException("ERR EXEC without MULTI"));
        return reply;
    }
}
