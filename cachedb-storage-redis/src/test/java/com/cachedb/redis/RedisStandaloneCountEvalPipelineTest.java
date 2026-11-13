package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.args.RawableFactory;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.RedisOutputStream;

class RedisStandaloneCountEvalPipelineTest {
    private static final String TENANT = "\u015firket";

    @Test void ownedEvalArgumentsMatchCompleteStockRespForUtf8AndUnpairedSurrogates() throws Exception {
        var text = List.of("", "ascii\u0000\r\n", "\u015firket:\u4e00", "\ud83d\ude00",
                "\ud800", "\udc00", "x\ud800y\udc00z");
        String script = "return ARGV[1]\n-- \u4e00 \ud800";
        for (int size : List.of(0, 1, 512, 1536, 2048)) {
            var keys = IntStream.range(0, size).mapToObj(i -> text.get(i % text.size()) + ":" + i).toList();
            var expected = stockEval(script, keys, text);
            var actual = RedisStandaloneIndexSnapshot.evalCommand(script, keys, text);
            assertArrayEquals(resp(expected), resp(actual));
            assertTrue(actual.getKeys().isEmpty());
            var iterator = actual.iterator();
            assertSame(Command.EVAL, iterator.next());
            for (int position = 1; iterator.hasNext(); position++) {
                var argument = iterator.next();
                assertSame(argument.getRaw(), argument.getRaw());
                if (position != 2) assertFalse(argument instanceof RawableFactory.Raw);
            }
        }
        for (int position : List.of(0, 1, 2)) {
            String nullableScript = position == 0 ? null : script;
            List<String> keys = position == 1 ? Arrays.asList((String) null) : List.of("key");
            List<String> arguments = position == 2 ? Arrays.asList((String) null) : List.of("value");
            var expected = assertThrows(IllegalArgumentException.class, () -> stockEval(nullableScript, keys, arguments));
            var actual = assertThrows(IllegalArgumentException.class,
                    () -> RedisStandaloneIndexSnapshot.evalCommand(nullableScript, keys, arguments));
            assertEquals(expected.getMessage(), actual.getMessage());
        }
    }

    @Test void ownedEvalBytesArePrivateToEachCommand() throws Exception {
        var first = RedisStandaloneIndexSnapshot.evalCommand("return ARGV[1]", List.of("key"), List.of("value"));
        var second = RedisStandaloneIndexSnapshot.evalCommand("return ARGV[1]", List.of("key"), List.of("value"));
        byte[] expected = resp(second);
        var a = first.iterator();
        var b = second.iterator();
        for (int position = 0; a.hasNext(); position++) {
            var left = a.next();
            var right = b.next();
            if (position == 0 || position == 2) continue;
            assertNotSame(left.getRaw(), right.getRaw());
            left.getRaw()[0] ^= 1;
        }
        assertArrayEquals(expected, resp(second));
    }

    @Test void ownedEvalsDoNotFreezeNestedMutableOwnerTemplates() throws Exception {
        var keys = List.of("owner:\u015firket:1", "owner:\u4e00:2");
        var owners = new RedisStandaloneIndexSnapshot.Phase();
        owners.addBoundedOwners(keys, 7);
        var nested = new RedisStandaloneIndexSnapshot.Phase();
        nested.add(RedisStandaloneIndexSnapshot.evalCommand("return 1", List.of("before"), List.of("a")));
        nested.append(owners);
        var phase = new RedisStandaloneIndexSnapshot.Phase();
        phase.add(new CommandArguments(Command.MULTI));
        phase.append(nested);
        phase.add(RedisStandaloneIndexSnapshot.evalCommand("return 2", List.of("after"), List.of("b")));
        var expected = List.of(new CommandArguments(Command.MULTI),
                stockEval("return 1", List.of("before"), List.of("a")),
                new CommandArguments(Command.GETRANGE).add(keys.get(0)).add(0).add(7),
                new CommandArguments(Command.GETRANGE).add(keys.get(1)).add(0).add(7),
                stockEval("return 2", List.of("after"), List.of("b")));
        assertEquals(expected.size(), phase.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(resp(expected.get(i)), resp(phase.commandAt(i)));
        assertSame(phase.commandAt(2), phase.commandAt(3));
        assertArrayEquals(resp(expected.get(2)), resp(phase.commandAt(2)));
        assertArrayEquals(resp(expected.get(3)), resp(phase.commandAt(3)));
    }

    @Test void ownedEvalCommandsKeepTheExactSixteenMiBPhaseAdmissionBoundary() {
        String script = "return ARGV[1]";
        var keys = List.of("key:\u4e00");
        long overhead = 0;
        for (var argument : stockEval(script, keys, List.of(""))) overhead += argument.getRaw().length + 16L;
        long commandBytes = RedisStandaloneIndexSnapshot.MAX_BYTES / 512;
        int payloadBytes = Math.toIntExact(commandBytes - overhead);
        String payload = "\u4e00".repeat(payloadBytes / 3) + "x".repeat(payloadBytes % 3);
        var stock = stockEval(script, keys, List.of(payload));
        var owned = RedisStandaloneIndexSnapshot.evalCommand(script, keys, List.of(payload));
        var expected = new RedisStandaloneIndexSnapshot.Phase();
        var actual = new RedisStandaloneIndexSnapshot.Phase();
        for (int i = 0; i < 512; i++) {
            expected.add(stock);
            actual.add(owned);
        }
        assertEquals(512, actual.size());
        var failure = assertThrows(RuntimeException.class, () -> expected.add(stock));
        assertEquals(failure.getClass(), assertThrows(RuntimeException.class, () -> actual.add(owned)).getClass());
        assertEquals(512, actual.size());
    }

    private static CommandArguments stockEval(String script, List<String> keys, List<String> arguments) {
        return new CommandArguments(Command.EVAL).add(script).add(keys.size()).addObjects(keys).addObjects(arguments);
    }

    private static byte[] resp(CommandArguments command) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new RedisOutputStream(bytes);
        Protocol.sendCommand(output, command);
        output.flush();
        return bytes.toByteArray();
    }

    @Test void tenThousandRowsUseTwentyUnchangedEvalsInOneBorrowAndReadPhase() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var replies = new ArrayList<Object>();
            for (int start = 0; start < 10_000; start += 512) replies.add(result(Math.min(512, 10_000 - start)));
            pool.connection.reply(replies);
            assertEquals(10_000L, RedisStandaloneIndexSnapshot.indexCountEvals(pool, 10_000,
                    start -> keys(start, Math.min(512, 10_000 - start)), TENANT).orElseThrow());
            assertEquals(1, pool.borrows);
            assertEquals(1, pool.connection.reads);
            assertEquals(1, pool.healthyReturns);
            assertEquals(0, pool.brokenReturns);
            var commands = pool.connection.phases.getFirst();
            assertEquals(20, commands.size());
            var request = RedisClientOperations.indexAggregateCommand(keys(0, 512).entities(), keys(0, 512).tombstones(),
                    keys(0, 512).indexMetadata(), keys(0, 512).owners(), TENANT, null, true);
            for (int i = 0; i < commands.size(); i++) {
                var command = commands.get(i);
                int size = Math.min(512, 10_000 - i * 512);
                assertEquals("EVAL", command.get(0));
                assertEquals(request.script(), command.get(1));
                assertEquals(Integer.toString(3 * size), command.get(2));
                assertEquals(List.of("", "1", Integer.toString(size), "1", TENANT), command.subList(command.size() - 5, command.size()));
                assertEquals("owner:" + i * 512, command.get(3 + 2 * size));
            }
        }
    }

    @Test void unownedWindowsPipelineWithNullOwnersAndUnusedNullMetadata() {
        for (int rows : List.of(513, 545, 1024, 1025, 10_000)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var replies = new ArrayList<Object>();
                for (int start = 0; start < rows; start += 512) replies.add(result(Math.min(512, rows - start)));
                pool.connection.reply(replies);
                assertEquals(rows, unownedCount(pool, rows).orElseThrow());
                assertEquals(1, pool.borrows);
                assertEquals(1, pool.connection.reads);
                assertEquals(1, pool.healthyReturns);
                var commands = pool.connection.phases.getFirst();
                assertEquals((rows + 511) / 512, commands.size());
                for (int i = 0; i < commands.size(); i++) {
                    var command = commands.get(i);
                    int size = Math.min(512, rows - i * 512);
                    var expected = RedisClientOperations.indexAggregateCommand(keys("entity:", i * 512, size),
                            keys("tomb:", i * 512, size), java.util.Collections.nCopies(size, null), null, null, null, true);
                    assertEquals("EVAL", command.get(0));
                    assertEquals(expected.script(), command.get(1));
                    assertEquals(Integer.toString(2 * size), command.get(2));
                    assertEquals(expected.keys(), command.subList(3, 3 + 2 * size));
                    assertEquals(expected.arguments(), command.subList(3 + 2 * size, command.size()));
                }
            }
        }
    }

    @Test void unownedEvalDenialNeverHidesLaterCorruptionOrReturnsAPartialCount() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(result(512), denied(), result(1)));
            assertTrue(unownedCount(pool, 1025).isEmpty());
            assertEquals(1, pool.brokenReturns);
            assertEquals(0, pool.healthyReturns);
        }
        for (Object invalid : List.of(new JedisDataException("WRONGTYPE"), result(2),
                List.of(0L, List.of(), List.of(0L)))) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(List.of(result(512), denied(), invalid));
                assertThrows(RuntimeException.class, () -> unownedCount(pool, 1025));
                assertEquals(1, pool.brokenReturns);
                assertEquals(0, pool.healthyReturns);
            }
        }
    }

    @Test void unownedCountRejectsOwnerKeysBeforeBorrowAndRetainsPhaseLimits() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            assertThrows(IllegalArgumentException.class, () -> RedisStandaloneIndexSnapshot.indexCountEvals(
                    pool, 1024, start -> keys(start, 512), null));
            assertEquals(0, pool.borrows);
            assertTrue(RedisStandaloneIndexSnapshot.indexCountEvals(pool,
                    512 * RedisStandaloneIndexSnapshot.MAX_COMMANDS + 1,
                    start -> { throw new AssertionError("No factory"); }, null).isEmpty());
            String prefix = "x".repeat(3400);
            assertTrue(RedisStandaloneIndexSnapshot.indexCountEvals(pool, 3072,
                    start -> new RedisClientOperations.IndexReadKeys(
                            java.util.Collections.nCopies(512, prefix + "e"),
                            java.util.Collections.nCopies(512, prefix + "t"),
                            java.util.Collections.nCopies(512, null), null), null).isEmpty());
            assertEquals(0, pool.borrows);
            assertEquals(0, pool.connection.sends);
        }
    }

    @Test void zeroIsAResultRatherThanAnUnsupportedCapability() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(result(0), result(0)));
            assertEquals(0L, RedisStandaloneIndexSnapshot.indexCountEvals(pool, 1024,
                    start -> keys(start, 512), TENANT).orElseThrow());
            assertEquals(1, pool.borrows);
            assertEquals(List.of("EVAL", "EVAL"), pool.connection.names());
        }
    }

    @Test void batchedCountResultDistinguishesZeroDenialAndUnsupported() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(result(0), result(0)));
            var result = RedisStandaloneIndexSnapshot.indexCountEvalResult(pool, 1024,
                    start -> keys(start, 512), TENANT);
            assertEquals(RedisStandaloneIndexSnapshot.CountEvalState.VALUE, result.state());
            assertEquals(0, result.value());
            assertNull(result.failure());
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var rejection = denied();
            pool.connection.reply(List.of(rejection,
                    new JedisAccessControlException("NOPERM No permissions to access a key")));
            var result = RedisStandaloneIndexSnapshot.indexCountEvalResult(pool, 1024,
                    start -> keys(start, 512), TENANT);
            assertEquals(RedisStandaloneIndexSnapshot.CountEvalState.EVAL_DENIED, result.state());
            assertSame(rejection, result.failure(), "Keep the first validated rejection, not a replacement");
            assertEquals(1, pool.connection.reads);
            assertEquals(1, pool.brokenReturns);
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var result = RedisStandaloneIndexSnapshot.indexCountEvalResult(pool, 512,
                    start -> { throw new AssertionError("No factory"); }, TENANT);
            assertEquals(RedisStandaloneIndexSnapshot.CountEvalState.UNSUPPORTED, result.state());
            assertNull(result.failure());
            assertEquals(0, pool.borrows);
        }
    }

    @Test void tenantEvalDenialCannotHideAnyOtherReplyFailure() {
        for (Object denied : List.of(denied(),
                new JedisAccessControlException("NOPERM No permissions to access a key"),
                new JedisAccessControlException("NOPERM this user has no permissions to access one of the keys used as arguments"))) {
            for (int position : List.of(0, 1, 2)) {
                try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                    var replies = new ArrayList<Object>(List.of(result(512), result(512), result(512)));
                    replies.set(position, denied);
                    replies.set((position + 1) % 3, new JedisDataException("WRONGTYPE owner"));
                    pool.connection.reply(replies);
                    assertEquals("WRONGTYPE owner", assertThrows(JedisDataException.class,
                            () -> RedisStandaloneIndexSnapshot.indexCountEvalResult(pool, 1536,
                                    start -> keys(start, 512), TENANT)).getMessage());
                    assertEquals(1, pool.borrows);
                    assertEquals(1, pool.connection.reads);
                    assertEquals(List.of("EVAL", "EVAL", "EVAL"), pool.connection.names());
                    assertEquals(1, pool.brokenReturns);
                }
            }
        }
    }

    @Test void innerAclErrorsAndDecoratedDirectDenialsAreNotCapabilityAbsence() {
        var caused = new JedisAccessControlException(denied().getMessage(),
                new IllegalStateException("uncertain origin"));
        var suppressed = denied();
        suppressed.addSuppressed(new IllegalStateException("uncertain state"));
        for (var failure : List.of(caused, suppressed,
                new JedisAccessControlException("NOPERM No permissions to access a key script: user_script:1"),
                new JedisDataException("ERR ACL failure in script"))) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(List.of(failure, result(512)));
                assertSame(failure, assertThrows(JedisDataException.class,
                        () -> RedisStandaloneIndexSnapshot.indexCountEvalResult(pool, 1024,
                                start -> keys(start, 512), TENANT)));
                assertEquals(1, pool.borrows);
                assertEquals(1, pool.brokenReturns);
            }
        }
    }

    @Test void wholePhaseByteOverflowFallsBackBeforeBorrowOrSend() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            String prefix = "x".repeat(3400);
            var unsupported = RedisStandaloneIndexSnapshot.indexCounts(pool, 2048,
                    start -> new RedisClientOperations.IndexReadKeys(
                            List.copyOf(java.util.Collections.nCopies(512, prefix + "e")),
                            List.copyOf(java.util.Collections.nCopies(512, prefix + "t")),
                            List.copyOf(java.util.Collections.nCopies(512, prefix + "m")),
                            List.copyOf(java.util.Collections.nCopies(512, prefix + "o"))), TENANT);
            assertTrue(unsupported.isEmpty());
            assertEquals(0, pool.borrows);
            assertEquals(0, pool.connection.sends);
        }
    }

    @Test void defaultCapabilityAndExcessivePhasesNeverEvaluateTheWindowFactory() {
        RedisClientOperations defaults = new RedisClientOperations() {
            @Override public redis.clients.jedis.JedisPooled commands() { throw new AssertionError("No client access"); }
            @Override public boolean clustered() { return true; }
        };
        assertTrue(defaults.aggregateIndexCounts(10_000, start -> { throw new AssertionError("No factory"); }, TENANT).isEmpty());
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            assertTrue(RedisStandaloneIndexSnapshot.indexCountEvals(pool, 512 * RedisStandaloneIndexSnapshot.MAX_COMMANDS + 1,
                    start -> { throw new AssertionError("No factory"); }, TENANT).isEmpty());
            assertEquals(0, pool.borrows);
        }
    }

    @Test void directEvalDenialFallsBackOnlyAfterValidatingEveryReplyAndDiscardingTheLease() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(denied(), result(7)));
            assertTrue(count(pool, 1024).isEmpty());
            assertEquals(0, pool.healthyReturns);
            assertEquals(1, pool.brokenReturns);
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(denied(), new JedisDataException("WRONGTYPE")));
            assertThrows(JedisDataException.class, () -> count(pool, 1024));
            assertEquals(1, pool.brokenReturns);
        }
    }

    @Test void invalidCountsAndNonEmptyCountStarNullListsCannotReturnAPartialTotal() {
        for (Object invalid : List.of(result(-1), result(513), result(1.5), List.of(0L, List.of(), List.of(0L)),
                List.of(1L, List.of("unexpected"), List.of()))) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(List.of(result(512), invalid));
                assertThrows(IllegalStateException.class, () -> count(pool, 1024));
                assertEquals(0, pool.healthyReturns);
                assertEquals(1, pool.brokenReturns);
            }
        }
    }

    @Test void transportAndPoolReturnFailuresNeverBecomeFallbacks() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.failureAtSend = 2;
            assertThrows(JedisConnectionException.class, () -> count(pool, 1024));
            assertEquals(1, pool.brokenReturns);
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.failureAtRead = 1;
            assertThrows(JedisConnectionException.class, () -> count(pool, 1024));
            assertEquals(1, pool.brokenReturns);
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(result(512), result(512)));
            pool.failReturn = true;
            assertThrows(JedisConnectionException.class, () -> count(pool, 1024));
        }
    }

    @Test void interruptBeforeDispatchCannotBorrowOrSend() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            Thread.currentThread().interrupt();
            try { assertThrows(CancellationException.class, () -> count(pool, 1024)); }
            finally { Thread.interrupted(); }
            assertEquals(0, pool.borrows);
            assertEquals(0, pool.connection.sends);
        }
    }

    @Test void facetTenThousandRowsShareOnePhaseAndOneSixteenMiBReplyBudget() {
        for (String field : Arrays.asList(null, "amount")) {
            int rows = field == null ? 512 : 256;
            int commands = (10_000 - 1) / rows + 1;
            long quota = facetQuota(10_000, rows);
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var replies = new ArrayList<Object>();
                for (int start = 0; start < 10_000; start += rows)
                    replies.add(facetResult(Math.min(rows, 10_000 - start), field));
                pool.connection.reply(replies);
                var batches = facets(pool, 10_000, field).orElseThrow();
                assertEquals(commands, batches.size());
                assertEquals(10_000, batches.stream().mapToInt(b -> b.rows().size()).sum());
                assertEquals(1, pool.borrows);
                assertEquals(1, pool.connection.reads);
                assertEquals(1, pool.healthyReturns);
                assertEquals(0, pool.brokenReturns);
                var phase = pool.connection.phases.getFirst();
                var window = keys(0, rows);
                var legacy = RedisClientOperations.facetIndexCommand(window.entities(), window.tombstones(),
                        window.indexMetadata(), window.owners(), TENANT, "group", field, 0);
                assertEquals(commands, phase.size());
                for (int i = 0; i < phase.size(); i++) {
                    var command = phase.get(i);
                    int size = Math.min(rows, 10_000 - i * rows);
                    assertEquals("EVAL", command.get(0));
                    assertEquals(legacy.script(), command.get(1));
                    assertEquals(Integer.toString(4 * size), command.get(2));
                    assertEquals(List.of("group", field == null ? "" : field, Integer.toString(size),
                            "1", TENANT, Long.toString(quota)), command.subList(command.size() - 6, command.size()));
                }
                long framing = 64L * (10_000 + commands);
                assertTrue(3L * commands * quota + framing <= 16L * 1024 * 1024);
                assertThrows(UnsupportedOperationException.class, () -> batches.clear());
            }
        }
    }

    @Test void emptyFacetBatchesArePresentAndDefaultFacetCapabilityDoesNotCallFactory() {
        RedisClientOperations unsupported = new RedisClientOperations() {
            public redis.clients.jedis.JedisPooled commands() { throw new AssertionError("No client access"); }
            public boolean clustered() { return true; }
        };
        assertTrue(unsupported.facetIndexBatches(1024, start -> {
            throw new AssertionError("No factory");
        }, TENANT, "group", null).isEmpty());
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(facetResult(0, null), facetResult(0, null)));
            var result = facets(pool, 1024, null).orElseThrow();
            assertEquals(2, result.size());
            assertTrue(result.stream().allMatch(batch -> batch.rows().isEmpty()));
        }
    }

    @Test void exactFacetQuotaMarkerInFirstMiddleOrFinalWindowDiscardsEveryPartialBatch() {
        for (int marker : List.of(0, 1, 2)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var replies = new ArrayList<Object>(List.of(facetResult(1, null), facetResult(1, null), facetResult(1, null)));
                replies.set(marker, quotaMarker(512, facetQuota(1536, 512)));
                pool.connection.reply(replies);
                assertTrue(facets(pool, 1536, null).isEmpty());
                assertEquals(1, pool.healthyReturns);
                assertEquals(0, pool.brokenReturns);
                assertEquals(3, pool.connection.sends);
                assertEquals(1, pool.connection.reads);
            }
        }
    }

    @Test void quotaAndDirectEvalDenialNeverMaskAnotherWindowFailure() {
        for (Object rejected : List.of(quotaMarker(512, facetQuota(1536, 512)), denied())) {
            for (int failureAt : List.of(0, 1, 2)) {
                try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                    var replies = new ArrayList<Object>(List.of(rejected, rejected, rejected));
                    replies.set(failureAt, new JedisDataException("Incomplete facet index metadata; rebuild indexes"));
                    pool.connection.reply(replies);
                    assertThrows(JedisDataException.class, () -> facets(pool, 1536, null));
                    assertEquals(0, pool.healthyReturns);
                    assertEquals(1, pool.brokenReturns);
                }
            }
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(denied(), facetResult(1, null)));
            assertTrue(facets(pool, 1024, null).isEmpty());
            assertEquals(1, pool.brokenReturns);
        }
    }

    @Test void forgedQuotaMarkersReplyShapeAndDecodedByteOverflowsFailClosed() {
        long quota = facetQuota(1024, 512);
        var oversizedPair = List.of(List.of(0L), List.of(Arrays.asList("x".repeat((int) quota + 1), null)));
        for (Object invalid : List.of(quotaMarker(511, quota), quotaMarker(512, quota + 1),
                List.of("cachedb-facet-phase-budget-v1", "512", quota),
                List.of("cachedb-facet-phase-budget-v1", 512L, (double) quota),
                List.of("cachedb-facet-phase-budget-v2", 512L, quota),
                List.of(List.of(512L), List.of(Arrays.asList("group", null))), oversizedPair)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(List.of(quotaMarker(512, quota), invalid));
                assertThrows(IllegalStateException.class, () -> facets(pool, 1024, null));
                assertEquals(1, pool.brokenReturns);
            }
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(facetResult(0, null)));
            assertThrows(IllegalStateException.class, () -> facets(pool, 1024, null));
            assertEquals(1, pool.brokenReturns);
        }
    }

    @Test void facetRequestOverflowAndExcessiveCommandsDeclineBeforeBorrow() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            String prefix = "x".repeat(2500);
            assertTrue(RedisStandaloneIndexSnapshot.facetIndexEvals(pool, 2048,
                    start -> new RedisClientOperations.IndexReadKeys(
                            java.util.Collections.nCopies(512, prefix + "e"),
                            java.util.Collections.nCopies(512, prefix + "t"),
                            java.util.Collections.nCopies(512, prefix + "m"),
                            java.util.Collections.nCopies(512, prefix + "o")), TENANT, "group", null).isEmpty());
            assertTrue(RedisStandaloneIndexSnapshot.facetIndexEvals(pool, 512 * RedisStandaloneIndexSnapshot.MAX_COMMANDS + 1,
                    start -> { throw new AssertionError("No factory"); }, TENANT, "group", null).isEmpty());
            assertEquals(0, pool.borrows);
            assertEquals(0, pool.connection.sends);
        }
    }

    @Test void facetTransportInterruptionAndReturnFailureNeverBecomeQuotaFallback() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.failureAtSend = 2;
            assertThrows(JedisConnectionException.class, () -> facets(pool, 1024, null));
            assertEquals(1, pool.brokenReturns);
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.failureAtRead = 1;
            assertThrows(JedisConnectionException.class, () -> facets(pool, 1024, null));
            assertEquals(1, pool.brokenReturns);
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(quotaMarker(512, facetQuota(1024, 512)), facetResult(1, null)));
            pool.failReturn = true;
            assertThrows(JedisConnectionException.class, () -> facets(pool, 1024, null));
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            Thread.currentThread().interrupt();
            try { assertThrows(CancellationException.class, () -> facets(pool, 1024, null)); }
            finally { Thread.interrupted(); }
            assertEquals(0, pool.borrows);
        }
    }

    private static java.util.Optional<List<RedisClientOperations.FacetIndexBatch>> facets(
            RedisStandaloneIndexSnapshotTest.FakePool pool, int size, String field) {
        int rows = field == null ? 512 : 256;
        return RedisStandaloneIndexSnapshot.facetIndexEvals(pool, size,
                start -> keys(start, Math.min(rows, size - start)), TENANT, "group", field);
    }

    private static long facetQuota(int candidates, int rows) {
        int commands = (candidates - 1) / rows + 1;
        return (16L * 1024 * 1024 - 64L * (candidates + commands)) / (3L * commands);
    }

    private static List<Object> quotaMarker(long rows, long quota) {
        return List.of("cachedb-facet-phase-budget-v1", rows, quota);
    }

    private static List<Object> facetResult(int size, String field) {
        var positions = new ArrayList<Long>();
        var pairs = new ArrayList<List<Object>>();
        for (int i = 0; i < size; i++) {
            positions.add((long) i);
            pairs.add(Arrays.asList(("g" + i % 20).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    field == null ? null : "0.125".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return List.of(positions, pairs);
    }

    private static java.util.OptionalLong unownedCount(RedisStandaloneIndexSnapshotTest.FakePool pool, int rows) {
        return RedisStandaloneIndexSnapshot.indexCountEvals(pool, rows, start -> {
            int size = Math.min(512, rows - start);
            return new RedisClientOperations.IndexReadKeys(keys("entity:", start, size), keys("tomb:", start, size),
                    java.util.Collections.nCopies(size, null), null);
        }, null);
    }

    private static java.util.OptionalLong count(RedisStandaloneIndexSnapshotTest.FakePool pool, int rows) {
        return RedisStandaloneIndexSnapshot.indexCountEvals(pool, rows,
                start -> keys(start, Math.min(512, rows - start)), TENANT);
    }

    private static RedisClientOperations.IndexReadKeys keys(int start, int size) {
        return new RedisClientOperations.IndexReadKeys(keys("entity:", start, size), keys("tomb:", start, size),
                keys("meta:", start, size), keys("owner:", start, size));
    }

    private static List<String> keys(String prefix, int start, int size) {
        return IntStream.range(start, start + size).mapToObj(i -> prefix + i).toList();
    }

    private static List<Object> result(Number count) { return List.of(count, List.of(), List.of()); }

    private static JedisAccessControlException denied() {
        return new JedisAccessControlException("NOPERM this user has no permissions to run the 'eval' command");
    }
}
