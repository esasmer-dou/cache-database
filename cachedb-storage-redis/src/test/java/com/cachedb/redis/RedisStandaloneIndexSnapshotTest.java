package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Connection;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.Pool;

class RedisStandaloneIndexSnapshotTest {
    private static final List<String> ENTITIES = List.of("entity:1", "entity:2");
    private static final List<String> TOMBS = List.of("tomb:1", "tomb:2");
    private static final List<String> META = List.of("meta:1", "meta:2");
    private static final List<String> OWNERS = List.of("owner:1", "owner:2");

    @Test void countUsesOneReadOnlyTransactionAndOneBorrowWithInclusiveUtf8OwnerProbe() {
        try (FakePool pool = new FakePool()) {
            String tenant = "\u015firket";
            pool.connection.reply(exec(2L, 0L, bytes(tenant), bytes(tenant + "x")));
            assertEquals(1, aggregate(pool, tenant, null, true).orElseThrow().count());
            assertEquals(List.of("MULTI", "EXISTS", "EXISTS", "GETRANGE", "GETRANGE", "EXEC"), pool.connection.names());
            assertEquals(List.of("GETRANGE", "owner:1", "0", Integer.toString(bytes(tenant).length)),
                    pool.connection.phases.get(0).get(3));
            assertEquals(1, pool.borrows);
            assertEquals(1, pool.healthyReturns);
            assertEquals(0, pool.brokenReturns);
        }
    }

    @Test void globalCountDoesNotInspectUnusedMetadataOrBorrowAnotherConnection() {
        try (FakePool pool = new FakePool()) {
            pool.connection.reply(exec(2L, 0L));
            assertEquals(2, RedisStandaloneIndexSnapshot.aggregate(pool, ENTITIES, TOMBS,
                    Arrays.asList(null, null), null, null, "*", true).orElseThrow().count());
            assertEquals(List.of("MULTI", "EXISTS", "EXISTS", "EXEC"), pool.connection.names());
            assertEquals(1, pool.borrows);
        }
    }

    @Test void numericSnapshotWatchesEveryKeyAndSizesOnlyAuthorizedMetadataBeforeGet() {
        try (FakePool pool = new FakePool()) {
            pool.connection.reply(List.of(bytes("OK"), 2L, 0L, bytes("a"), bytes("b")));
            pool.connection.reply(lengths(5L));
            metadataExec(pool, metadataProof("a", "b"), List.of(5L), bytes("12.50"));
            var result = aggregate(pool, "a", "amount", false).orElseThrow();
            assertEquals(1, result.count());
            assertEquals(List.of("12.50"), result.values());
            assertEquals(1, pool.borrows);
            var watched = pool.connection.phases.get(0).get(0);
            var expected = new ArrayList<String>(); expected.add("WATCH");
            expected.addAll(ENTITIES); expected.addAll(TOMBS); expected.addAll(META); expected.addAll(OWNERS);
            assertEquals(expected, watched);
            assertEquals(List.of(List.of("HSTRLEN", "meta:1", "amount"), List.of("MULTI")), pool.connection.phases.get(1));
            assertEquals(List.of("EXISTS", "EXISTS", "GETRANGE", "GETRANGE", "HSTRLEN", "HGET", "EXEC"),
                    pool.connection.phases.get(2).stream().map(c -> c.get(0)).toList());
            assertEquals(List.of(5, 2, 4, 1, 2), pool.connection.requestedReplies);
            assertEquals(1, pool.healthyReturns);
        }
    }

    @Test void watchAbortReturnsFallbackSignalWithoutRetryOrRetainingDirtyState() {
        try (FakePool pool = new FakePool()) {
            owned(pool, "a", "a");
            pool.connection.reply(lengths(1L, 1L));
            pool.connection.reply(Collections.nCopies(4, bytes("QUEUED")));
            pool.connection.reply(Collections.nCopies(2, bytes("QUEUED")));
            pool.connection.reply(abort(2));
            assertTrue(aggregate(pool, "a", "amount", false).isEmpty());
            assertEquals(1, pool.borrows);
            assertEquals(1, pool.healthyReturns);
            assertEquals(0, pool.brokenReturns);
            assertEquals(1, Collections.frequency(pool.connection.names(), "EXEC"));
        }
    }

    @Test void mixedLivenessUnwatchesBeforeFallbackAndNeverClaimsZero() {
        for (var counts : List.of(List.of(1L, 0L), List.of(2L, 1L))) {
            try (FakePool pool = new FakePool()) {
                pool.connection.reply(List.of(bytes("OK"), counts.get(0), counts.get(1),
                        new JedisDataException("ignored dead owner"), bytes("b")));
                pool.connection.reply(List.of(bytes("OK")));
                assertTrue(aggregate(pool, "a", "amount", false).isEmpty());
                assertEquals("UNWATCH", pool.connection.names().get(pool.connection.names().size() - 1));
                assertFalse(pool.connection.names().contains("HSTRLEN"));
                assertFalse(pool.connection.names().contains("HGET"));
                assertEquals(1, pool.healthyReturns);
            }
        }
        try (FakePool pool = new FakePool()) {
            pool.connection.reply(exec(0L, 0L, bytes("a"), bytes("a")));
            assertTrue(aggregate(pool, "a", null, true).isEmpty());
        }
    }

    @Test void allForeignOwnersReplayEntireProofWithoutReadingMetadata() {
        try (FakePool pool = new FakePool()) {
            owned(pool, "b", "c");
            pool.connection.reply(lengths());
            metadataExec(pool, metadataProof("b", "c"), List.of());
            assertEquals(0, aggregate(pool, "a", "amount", false).orElseThrow().count());
            assertEquals(List.of("MULTI"), pool.connection.phases.get(1).stream().map(c -> c.get(0)).toList());
            assertEquals(List.of("EXISTS", "EXISTS", "GETRANGE", "GETRANGE", "EXEC"),
                    pool.connection.phases.get(2).stream().map(c -> c.get(0)).toList());
            assertFalse(pool.connection.names().contains("HSTRLEN"));
            assertFalse(pool.connection.names().contains("HGET"));
            assertEquals(List.of(5, 1, 4, 1), pool.connection.requestedReplies);
            assertEquals(1, pool.healthyReturns);
        }
    }

    @Test void metadataByteLimitIsCheckedBeforeAnyHgetAndDirtyLeaseIsInvalidated() {
        try (FakePool pool = new FakePool()) {
            owned(pool, "a", "a");
            pool.connection.reply(lengths(RedisStandaloneIndexSnapshot.MAX_BYTES, 1L));
            assertThrows(JedisDataException.class, () -> aggregate(pool, "a", "amount", false));
            assertFalse(pool.connection.names().contains("HGET"));
            assertTrue(pool.connection.names().contains("MULTI"));
            assertTrue(pool.connection.isBroken());
            assertEquals(1, pool.brokenReturns);
            assertEquals(0, pool.healthyReturns);
        }
    }

    @Test void incompleteOrWronglyTypedMetadataCannotBecomeAValidRow() {
        for (Object value : Arrays.asList(null, new JedisDataException("WRONGTYPE"))) {
            try (FakePool pool = new FakePool()) {
                owned(pool, "a", "b");
                pool.connection.reply(lengths(0L));
                metadataExec(pool, metadataProof("a", "b"), List.of(0L), value);
                assertThrows(JedisDataException.class, () -> aggregate(pool, "a", "amount", false));
                // Successful EXEC has drained and cleared WATCH even when a field result is invalid.
                assertEquals(1, pool.healthyReturns);
            }
        }
        try (FakePool pool = new FakePool()) {
            owned(pool, "a", "b");
            pool.connection.reply(lengths(1L));
            metadataExec(pool, metadataProof("a", "b"), List.of(1L), bytes("too-long"));
            assertThrows(IllegalStateException.class, () -> aggregate(pool, "a", "amount", false));
        }
    }

    @Test void literalNullAndEmptyMetadataKeepOriginalPositionsAndDecimalText() {
        try (FakePool pool = new FakePool()) {
            owned(pool, "a", "a");
            pool.connection.reply(lengths(8L, 0L));
            metadataExec(pool, metadataProof("a", "a"), List.of(8L, 0L), bytes("__NULL__"), bytes(""));
            var result = aggregate(pool, "a", "amount", true).orElseThrow();
            assertEquals(1, result.count());
            assertEquals(List.of(0), result.nullCandidates());
            assertEquals(List.of(), result.values());
        }
        try (FakePool pool = new FakePool()) {
            owned(pool, "a", "a");
            pool.connection.reply(lengths(8L, 8L, 0L, 7L));
            metadataExec(pool, metadataProof("a", "a"), List.of(8L, 8L, 0L, 7L),
                    bytes("__NULL__"), bytes("__NULL__"), bytes(""), bytes("0.00001"));
            var result = RedisStandaloneIndexSnapshot.facet(pool, ENTITIES, TOMBS, META, OWNERS,
                    "a", "group", "amount").orElseThrow();
            assertEquals(List.of(new RedisClientOperations.FacetIndexRow(0, "__NULL__", "__NULL__"),
                    new RedisClientOperations.FacetIndexRow(1, "", "0.00001")), result.rows());
            assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
        }
    }

    @Test void countFieldDoesNotImposeReturnedValueCharacterBudgetOrDecodeUnreturnedText() {
        try (FakePool pool = new FakePool()) {
            byte[] raw = new byte[9 * 1024 * 1024]; Arrays.fill(raw, (byte) 'x');
            owned(pool, "a", "b");
            pool.connection.reply(lengths((long) raw.length));
            metadataExec(pool, metadataProof("a", "b"), List.of((long) raw.length), raw);
            var result = aggregate(pool, "a", "label", true).orElseThrow();
            assertEquals(1, result.count());
            assertTrue(result.values().isEmpty());
            assertEquals(1, pool.healthyReturns);
        }
    }

    @Test void ioAndQueueFailuresDropConnectionWithoutRetry() {
        try (FakePool pool = new FakePool()) {
            owned(pool, "a", "a");
            pool.connection.failureAtRead = 2;
            assertThrows(JedisConnectionException.class, () -> aggregate(pool, "a", "amount", false));
            assertEquals(1, pool.borrows);
            assertEquals(1, pool.brokenReturns);
            assertEquals(0, pool.healthyReturns);
        }
        try (FakePool pool = new FakePool()) {
            pool.connection.reply(Arrays.asList(bytes("OK"), new JedisDataException("NOPERM"),
                    bytes("QUEUED"), bytes("QUEUED"), bytes("QUEUED"), new JedisDataException("EXECABORT")));
            assertThrows(JedisDataException.class, () -> aggregate(pool, "a", null, true));
            assertEquals(1, pool.brokenReturns);
            assertTrue(pool.connection.isBroken());
        }
    }

    @Test void sendFailureAndMalformedEnvelopeCannotReturnHealthySocket() {
        try (FakePool pool = new FakePool()) {
            pool.connection.failureAtSend = 3;
            assertThrows(JedisConnectionException.class, () -> aggregate(pool, "a", null, true));
            assertEquals(1, pool.brokenReturns);
        }
        try (FakePool pool = new FakePool()) {
            pool.connection.reply(List.of(bytes("unexpected")));
            assertThrows(IllegalStateException.class, () -> aggregate(pool, "a", null, true));
            assertEquals(1, pool.brokenReturns);
        }
    }

    @Test void deniedMultiCannotSendAnyHgetOutsideTheTransaction() {
        try (FakePool pool = new FakePool()) {
            owned(pool, "a", "a");
            pool.connection.reply(List.of(1L, 1L, new JedisDataException("NOPERM MULTI")));
            assertThrows(JedisDataException.class, () -> aggregate(pool, "a", "amount", false));
            assertFalse(pool.connection.names().contains("HGET"));
            assertFalse(pool.connection.names().contains("EXEC"));
            assertEquals(1, pool.brokenReturns);
        }
    }

    @Test void metadataExecRequiresAcknowledgedMultiBeforeAnyHgetCanBeSent() {
        try (FakePool pool = new FakePool(); var lease = RedisStandaloneIndexSnapshot.Lease.open(pool)) {
            var reads = new RedisStandaloneIndexSnapshot.Phase();
            reads.add(new CommandArguments(redis.clients.jedis.Protocol.Command.HGET).key("meta").add("amount"));
            assertThrows(IllegalStateException.class, () -> lease.exec(reads, true));
            assertThrows(IllegalStateException.class, () -> lease.execMetadata(
                    new RedisStandaloneIndexSnapshot.Phase(), new RedisStandaloneIndexSnapshot.Phase(), reads));
            assertEquals(0, pool.connection.sends);
        }
    }

    @Test void disconnectedLeaseCannotImplicitlyReconnectBetweenPhases() {
        try (FakePool pool = new FakePool()) {
            owned(pool, "a", "a");
            pool.connection.disconnectAfterReads = 1;
            assertThrows(JedisConnectionException.class, () -> aggregate(pool, "a", "amount", false));
            assertEquals(1, pool.connection.phases.size());
            assertEquals(1, pool.borrows);
            assertEquals(1, pool.brokenReturns);
        }
    }

    @Test void rowFieldAndRequestByteBoundsAreCheckedBeforeBorrow() {
        try (FakePool pool = new FakePool()) {
            var many = Collections.nCopies(513, "k");
            assertThrows(IllegalArgumentException.class, () -> RedisStandaloneIndexSnapshot.aggregate(
                    pool, many, many, many, null, null, null, true));
            var keys = Collections.nCopies(257, "k");
            assertTrue(RedisStandaloneIndexSnapshot.facet(pool, keys, keys, keys, null, null, "g", "v").isEmpty());
            var wide = Collections.nCopies(512, "\u4e00".repeat(6000));
            assertThrows(IllegalArgumentException.class, () -> RedisStandaloneIndexSnapshot.aggregate(
                    pool, wide, wide, wide, null, null, null, true));
            assertEquals(0, pool.borrows);
        }
    }

    @Test void nativeRepeatedKeyWireBudgetSelectsCompactLuaFallbackBeforeSending() {
        try (FakePool pool = new FakePool()) {
            var entities = Collections.nCopies(512, "\u4e00".repeat(2000));
            var tombs = Collections.nCopies(512, "\u4e00".repeat(2000));
            var meta = Collections.nCopies(512, "\u4e00".repeat(6000));
            assertTrue(RedisStandaloneIndexSnapshot.aggregate(pool, entities, tombs, meta,
                    null, null, "amount", false).isEmpty());
            assertEquals(0, pool.connection.sends);
            assertEquals(1, pool.healthyReturns);
        }
    }

    @Test void nativeShapeFallbackCannotHideConnectionReturnFailure() {
        try (FakePool pool = new FakePool()) {
            pool.failReturn = true;
            var entities = Collections.nCopies(512, "\u4e00".repeat(2000));
            var meta = Collections.nCopies(512, "\u4e00".repeat(6000));
            assertThrows(JedisConnectionException.class, () -> RedisStandaloneIndexSnapshot.aggregate(
                    pool, entities, entities, meta, null, null, "amount", false));
            assertEquals(0, pool.connection.sends);
        }
    }

    @Test void maximumCountPhaseIsBoundedAndUsesOnlyExplicitReadAndControlCommands() {
        try (FakePool pool = new FakePool()) {
            var keys = Collections.nCopies(512, "k");
            var proof = new ArrayList<Object>(); proof.add(512L); proof.add(0L);
            for (int i = 0; i < 512; i++) proof.add(bytes("a"));
            pool.connection.reply(exec(proof.toArray()));
            assertEquals(512, RedisStandaloneIndexSnapshot.aggregate(
                    pool, keys, keys, keys, keys, "a", null, true).orElseThrow().count());
            assertEquals(516, pool.connection.phases.get(0).size());
            assertTrue(Set.of("MULTI", "EXEC", "EXISTS", "GETRANGE").containsAll(pool.connection.names()));
        }
    }

    @Test void finiteTimeoutsArePreservedAndInfiniteDefaultsAreLocallyBoundedThenRestored() {
        try (FakePool pool = new FakePool()) {
            pool.setMaxWait(Duration.ofMillis(-1));
            pool.connection.setSoTimeout(0);
            pool.connection.reply(exec(2L, 0L));
            RedisStandaloneIndexSnapshot.aggregate(pool, ENTITIES, TOMBS, META, null, null, null, true);
            assertEquals(Duration.ofSeconds(2), pool.wait);
            assertEquals(List.of(2000), pool.connection.readTimeouts);
            assertEquals(0, pool.connection.getSoTimeout());
        }
        try (FakePool pool = new FakePool()) {
            pool.setMaxWait(Duration.ofMillis(23));
            pool.connection.setSoTimeout(97);
            pool.connection.reply(exec(2L, 0L));
            RedisStandaloneIndexSnapshot.aggregate(pool, ENTITIES, TOMBS, META, null, null, null, true);
            assertEquals(Duration.ofMillis(23), pool.wait);
            assertEquals(List.of(97), pool.connection.readTimeouts);
            assertEquals(97, pool.connection.getSoTimeout());
        }
    }

    @Test void interruptionDoesNotBorrowOrLeakThreadInterruptState() {
        try (FakePool pool = new FakePool()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class, () -> aggregate(pool, "a", null, true));
                assertEquals(0, pool.borrows);
            } finally { Thread.interrupted(); }
        }
    }

    @Test void nativeCountPipelinesTwentyBoundedTransactionsBeforeReadingOnOneLease() {
        try (FakePool pool = new FakePool()) {
            int[] factories = {0};
            for (int start = 0; start < 10_000; start += 512)
                pool.connection.reply(countExec(Math.min(512, 10_000 - start), "\u015firket"));
            assertEquals(10_000, RedisStandaloneIndexSnapshot.indexCounts(pool, 10_000, start -> {
                factories[0]++;
                return countKeys(start, Math.min(512, 10_000 - start));
            }, "\u015firket").orElseThrow());
            assertEquals(20, factories[0]);
            assertEquals(1, pool.borrows, "Healthy owned COUNT uses one native lease without EVAL");
            assertEquals(20, pool.connection.reads);
            assertEquals(10_080, pool.connection.phases.getFirst().size());
            assertEquals(20, Collections.frequency(pool.connection.names(), "MULTI"));
            assertEquals(20, Collections.frequency(pool.connection.names(), "EXEC"));
            assertEquals(0, Collections.frequency(pool.connection.names(), "EVAL"));
            assertTrue(pool.connection.requestedReplies.stream().allMatch(size -> size <= 516));
            var commands = pool.connection.phases.getFirst();
            assertEquals(List.of("GETRANGE", "owner:0", "0", "7"), commands.get(3));
            assertEquals(List.of("GETRANGE", "owner:512", "0", "7"), commands.get(519));
            assertEquals(1, pool.healthyReturns);
            assertEquals(0, pool.brokenReturns);
        }
    }

    @Test void nativeCountExactUtf8OwnersRejectMissingForeignAndOversizedPrefixes() {
        String tenant = "\u015firket";
        try (FakePool pool = new FakePool()) {
            var first = countExec(512, tenant);
            var values = new ArrayList<Object>((List<?>) first.getLast());
            values.set(2, bytes(""));
            values.set(3, bytes("foreign"));
            values.set(4, bytes(tenant + "x"));
            first.set(first.size() - 1, values);
            pool.connection.reply(first);
            pool.connection.reply(countExec(512, tenant));
            assertEquals(1021, nativeCount(pool, 1024, tenant).orElseThrow());
            assertEquals(1, pool.borrows);
            assertEquals(0, pool.brokenReturns);
        }
    }

    @Test void nativeCountFreezesEachActualKeyWindowBeforeTheFactoryCanReuseItsLists() {
        try (FakePool pool = new FakePool()) {
            pool.connection.reply(countExec(512, "a"));
            pool.connection.reply(countExec(512, "a"));
            var entities = new ArrayList<String>(); var tombs = new ArrayList<String>();
            var owners = new ArrayList<String>();
            assertEquals(1024, RedisStandaloneIndexSnapshot.indexCounts(pool, 1024, start -> {
                var keys = countKeys(start, 512);
                entities.clear(); entities.addAll(keys.entities());
                tombs.clear(); tombs.addAll(keys.tombstones());
                owners.clear(); owners.addAll(keys.owners());
                return new RedisClientOperations.IndexReadKeys(entities, tombs, keys.indexMetadata(), owners);
            }, "a").orElseThrow());
            var commands = pool.connection.phases.getFirst();
            assertEquals("entity:0", commands.get(1).get(1));
            assertEquals("entity:512", commands.get(517).get(1));
            assertEquals("owner:0", commands.get(3).get(1));
            assertEquals("owner:512", commands.get(519).get(1));
        }
    }

    @Test void mixedNativeCountDrainsAllWindowsAndRethrowsActualEvalDenialWithoutRetry() {
        try (FakePool pool = new FakePool()) {
            var rejection = denied("eval");
            pool.fallback.reply(Collections.nCopies(3, rejection));
            pool.connection.reply(countExec(512, "a"));
            var mixed = countExec(512, "a");
            var values = new ArrayList<Object>((List<?>) mixed.getLast());
            values.set(0, 511L);
            values.set(2, new JedisDataException("WRONGTYPE dead owner"));
            mixed.set(mixed.size() - 1, values);
            pool.connection.reply(mixed);
            pool.connection.reply(countExec(512, "a"));
            int[] factories = {0};
            assertSame(rejection, assertThrows(JedisAccessControlException.class,
                    () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1536, start -> {
                factories[0]++;
                return countKeys(start, 512);
            }, "a")));
            assertEquals(3, factories[0]);
            assertEquals(3, pool.connection.reads);
            assertEquals(1, pool.fallback.reads);
            assertEquals(2, pool.borrows);
            assertEquals(2, pool.brokenReturns, "Both rejected algorithms discard their leases");
            assertEquals(0, pool.healthyReturns);
            assertEquals(Collections.nCopies(3, "EVAL"), pool.fallback.names(), "No retry after verified denial");
        }
    }

    @Test void nativeCommandAndOwnerSelectorDenialsDrainEveryWindowBeforeEvalWithoutRetry() {
        for (String command : List.of("MULTI", "EXEC", "GETRANGE", "OWNER_KEY")) {
            try (FakePool pool = new FakePool()) {
                var rejection = denied("eval");
                pool.fallback.reply(Collections.nCopies(3, rejection));
                for (int i = 0; i < 3; i++) {
                    var reply = deniedCount(command);
                    if (command.equals("EXEC") && i > 0)
                        reply.set(0, new JedisDataException("ERR MULTI calls can not be nested"));
                    pool.connection.reply(reply);
                }
                assertSame(rejection, assertThrows(JedisAccessControlException.class,
                        () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1536,
                                start -> countKeys(start, 512), "a")), command);
                assertEquals(3, pool.connection.reads);
                assertEquals(1, pool.fallback.reads);
                assertEquals(Collections.nCopies(3, "EVAL"), pool.fallback.names());
                assertEquals(2, pool.borrows);
                assertEquals(2, pool.brokenReturns);
                assertEquals(0, pool.healthyReturns);
            }
        }
    }

    @Test void firstMiddleAndFinalEvalSelectorDenialsSurviveNativePrimaryDecline() {
        for (int position : List.of(0, 1, 2)) {
            for (String message : List.of("NOPERM this user has no permissions to run the 'eval' command",
                    "NOPERM No permissions to access a key",
                    "NOPERM this user has no permissions to access one of the keys used as arguments")) {
                try (FakePool pool = new FakePool()) {
                    var rejection = new JedisAccessControlException(message);
                    var evaluations = new ArrayList<Object>(Collections.nCopies(3, evalCount(512)));
                    evaluations.set(position, rejection);
                    pool.fallback.reply(evaluations);
                    for (int i = 0; i < 3; i++) pool.connection.reply(deniedCount("GETRANGE"));
                    assertSame(rejection, assertThrows(JedisAccessControlException.class,
                            () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 1536,
                                    start -> countKeys(start, 512), "a")));
                    assertEquals(1, pool.fallback.reads);
                    assertEquals(3, pool.connection.reads);
                    assertEquals(2, pool.borrows);
                    assertEquals(2, pool.brokenReturns);
                    assertEquals(0, pool.healthyReturns);
                    assertEquals(Collections.nCopies(3, "EVAL"), pool.fallback.names(), "No retry after both algorithms are unavailable");
                }
            }
        }
    }

    @Test void nativeBudgetDeclineRetainsTheActualEvalAclFailure() {
        try (FakePool pool = new FakePool()) {
            var rejection = denied("eval");
            pool.connection.reply(Collections.nCopies(8, rejection));
            assertSame(rejection, assertThrows(JedisAccessControlException.class,
                    () -> RedisStandaloneIndexSnapshot.indexCounts(pool, 4096,
                            start -> countKeys(start, 512), "x".repeat(4000))));
            assertEquals(1, pool.borrows, "Native budget decline must not borrow or send");
            assertEquals(1, pool.brokenReturns);
            assertEquals(1, pool.connection.reads);
            assertEquals(Collections.nCopies(8, "EVAL"), pool.connection.names());
        }
    }

    @Test void authorizedNativePrimaryZeroRemainsAValueWithoutEval() {
        try (FakePool pool = new FakePool()) {
            for (int i = 0; i < 2; i++) pool.connection.reply(countExec(512, "b"));
            assertEquals(0, RedisStandaloneIndexSnapshot.indexCounts(pool, 1024,
                    start -> countKeys(start, 512), "a").orElseThrow());
            assertEquals(1, pool.borrows);
            assertEquals(0, pool.brokenReturns);
            assertEquals(1, pool.healthyReturns);
            assertFalse(pool.connection.names().contains("EVAL"));
        }
    }

    @Test void nativeFallbackCannotHideMalformedOrWrongtypeInFirstMiddleOrFinalWindow() {
        for (int position : List.of(0, 1, 2))
                for (String invalid : List.of("status", "exec-size", "phase-size", "owner-size", "wrongtype")) {
            try (FakePool pool = new FakePool()) {
                for (int i = 0; i < 3; i++) {
                    var reply = i == (position + 1) % 3 ? deniedCount("GETRANGE") : countExec(512, "a");
                    if (i == position) {
                        if (invalid.equals("wrongtype") || invalid.equals("owner-size") || invalid.equals("exec-size")) {
                            var values = new ArrayList<Object>((List<?>) reply.getLast());
                            if (invalid.equals("exec-size")) values.removeLast();
                            else values.set(2, invalid.equals("wrongtype")
                                    ? new JedisDataException("WRONGTYPE owner") : bytes("aaa"));
                            reply.set(reply.size() - 1, values);
                        } else if (invalid.equals("phase-size")) reply.removeLast();
                        else reply.set(0, bytes("not-OK"));
                    }
                    pool.connection.reply(reply);
                }
                var failure = assertThrows(RuntimeException.class, () -> nativeCount(pool, 1536, "a"));
                if (invalid.equals("wrongtype")) assertEquals("WRONGTYPE owner", failure.getMessage());
                else assertInstanceOf(IllegalStateException.class, failure);
                assertEquals(3, pool.connection.reads);
                assertEquals(1, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertFalse(pool.connection.names().contains("EVAL"));
            }
        }
    }

    @Test void nativeExistsKeyDenialIsNotAnOwnerCapabilityFallback() {
        try (FakePool pool = new FakePool()) {
            var denied = countExec(512, "a");
            denied.set(1, new JedisAccessControlException("NOPERM No permissions to access a key"));
            denied.set(denied.size() - 1,
                    new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
            pool.connection.reply(denied);
            pool.connection.reply(countExec(512, "a"));
            assertThrows(JedisAccessControlException.class, () -> nativeCount(pool, 1024, "a"));
            assertEquals(2, pool.connection.reads);
            assertEquals(1, pool.brokenReturns);
            assertFalse(pool.connection.names().contains("EVAL"));
        }
    }

    @Test void nativeCountWholeOperationBudgetIncludesFramingAndRequestBeforeBorrow() {
        try (FakePool pool = new FakePool()) {
            String tenant = "x".repeat(4000);
            assertTrue(4096L * (bytes(tenant).length + 1) < RedisStandaloneIndexSnapshot.MAX_BYTES);
            pool.connection.reply(Collections.nCopies(8, evalCount(512)));
            assertEquals(4096, RedisStandaloneIndexSnapshot.indexCounts(pool, 4096,
                    start -> countKeys(start, 512), tenant).orElseThrow());
            assertEquals(1, pool.borrows, "Native byte-budget decline must not borrow or send before EVAL");
            assertEquals(Collections.nCopies(8, "EVAL"), pool.connection.names());
            assertEquals(1, pool.healthyReturns);
        }
    }

    @Test void nativeCountTenThousandBoundKeepsExistingLargerEvalCapability() {
        try (FakePool pool = new FakePool()) {
            pool.connection.reply(Collections.nCopies(20, evalCount(512)));
            assertEquals(10_240, RedisStandaloneIndexSnapshot.indexCounts(pool, 10_240,
                    start -> countKeys(start, 512), "a").orElseThrow());
            assertEquals(Collections.nCopies(20, "EVAL"), pool.connection.names());
            assertEquals(1, pool.borrows);
        }
    }

    @Test void nativeCountMidSendReadFailureOrCancellationDiscardsWithoutFallback() {
        for (int mode = 0; mode < 4; mode++) {
            try (FakePool pool = new FakePool()) {
                pool.connection.failureAtSend = mode == 0 ? 520 : -1;
                pool.connection.failureAtRead = mode == 1 ? 2 : -1;
                pool.connection.interruptAtSend = mode == 2 ? 520 : -1;
                pool.connection.interruptAtRead = mode == 3 ? 1 : -1;
                for (int i = 0; i < 3; i++) pool.connection.reply(countExec(512, "a"));
                try {
                    var failure = assertThrows(RuntimeException.class, () -> nativeCount(pool, 1536, "a"));
                    if (mode >= 2) assertInstanceOf(CancellationException.class, failure);
                    else assertInstanceOf(JedisConnectionException.class, failure);
                    assertEquals(1, pool.borrows);
                    assertEquals(1, pool.brokenReturns);
                    assertEquals(0, pool.healthyReturns);
                    assertFalse(pool.connection.names().contains("EVAL"));
                } finally { Thread.interrupted(); }
            }
        }
    }

    @Test void unownedNativeCountPipelinesNullableOwnerWindowsAtEveryBoundary() {
        for (int size : List.of(513, 545, 1024, 1025, 10_000)) {
            try (FakePool pool = new FakePool()) {
                int windows = (size + 511) / 512;
                for (int start = 0; start < size; start += 512)
                    pool.connection.reply(exec((long) Math.min(512, size - start), 0L));
                int[] factories = {0};
                assertEquals(size, RedisStandaloneIndexSnapshot.indexCounts(pool, size, start -> {
                    factories[0]++;
                    return unownedCountKeys(start, Math.min(512, size - start));
                }, null).orElseThrow());
                assertEquals(windows, factories[0]);
                assertEquals(1, pool.borrows);
                assertEquals(windows, pool.connection.reads);
                assertEquals(4 * windows, pool.connection.phases.getFirst().size(),
                        "Every transaction must be sent before the first reply is read");
                assertEquals(Collections.nCopies(windows, 4), pool.connection.requestedReplies);
                assertEquals(windows, Collections.frequency(pool.connection.names(), "MULTI"));
                assertEquals(windows, Collections.frequency(pool.connection.names(), "EXEC"));
                assertEquals(2 * windows, Collections.frequency(pool.connection.names(), "EXISTS"));
                assertTrue(pool.connection.names().stream().allMatch(
                        name -> List.of("MULTI", "EXISTS", "EXEC").contains(name)));
                var commands = pool.connection.phases.getFirst();
                for (int window = 0; window < windows; window++) {
                    int start = 512 * window, length = Math.min(512, size - start);
                    assertEquals("entity:" + start, commands.get(4 * window + 1).get(1));
                    assertEquals("tomb:" + (start + length - 1), commands.get(4 * window + 2).getLast());
                    assertEquals(length + 1, commands.get(4 * window + 1).size());
                    assertEquals(length + 1, commands.get(4 * window + 2).size());
                }
                assertEquals(1, pool.healthyReturns);
                assertEquals(0, pool.brokenReturns);
            }
        }
    }

    @Test void unownedNativeCountRetainsSmallWindowDeclineAndRejectsInvalidOwnerOrTailWindows() {
        for (int size : List.of(1, 512)) {
            try (FakePool pool = new FakePool()) {
                assertTrue(RedisStandaloneIndexSnapshot.indexCounts(pool, size,
                        start -> { throw new AssertionError("Small phase must decline before its factory"); }, null).isEmpty());
                assertEquals(0, pool.borrows);
            }
        }
        for (boolean invalidOwner : List.of(false, true)) {
            try (FakePool pool = new FakePool()) {
                assertThrows(IllegalArgumentException.class, () -> RedisStandaloneIndexSnapshot.indexCounts(
                        pool, 513, start -> invalidOwner ? countKeys(start, Math.min(512, 513 - start))
                                : unownedCountKeys(start, 512), null));
                assertEquals(0, pool.borrows);
            }
        }
    }

    @Test void unownedNativeCountMixedLivenessFallsBackForEveryDrainedWindow() {
        for (int position : List.of(0, 1, 2)) for (boolean tombstone : List.of(false, true)) {
            try (FakePool pool = new FakePool()) {
                for (int i = 0; i < 3; i++)
                    pool.connection.reply(exec(i == position && !tombstone ? 511L : 512L,
                            i == position && tombstone ? 1L : 0L));
                pool.fallback.reply(List.of(evalCount(position == 0 ? 511 : 512),
                        evalCount(position == 1 ? 511 : 512), evalCount(position == 2 ? 511 : 512)));
                int[] factories = {0};
                assertEquals(1535, RedisStandaloneIndexSnapshot.indexCounts(pool, 1536, start -> {
                    factories[0]++;
                    return unownedCountKeys(start, 512);
                }, null).orElseThrow());
                assertEquals(3, factories[0]);
                assertEquals(3, pool.connection.reads);
                assertEquals(1, pool.fallback.reads);
                assertEquals(Collections.nCopies(3, "EVAL"), pool.fallback.names());
                assertEquals(2, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertEquals(1, pool.healthyReturns);
            }
        }
    }

    @Test void unownedNativeCountTransactionDenialsDrainEveryWindowBeforeLuaFallback() {
        for (String command : List.of("MULTI", "EXEC")) {
            try (FakePool pool = new FakePool()) {
                for (int i = 0; i < 3; i++) {
                    var reply = unownedDeniedCount(command);
                    if (command.equals("EXEC") && i > 0)
                        reply.set(0, new JedisDataException("ERR MULTI calls can not be nested"));
                    pool.connection.reply(reply);
                }
                pool.fallback.reply(List.of(evalCount(512), evalCount(512), evalCount(512)));
                assertEquals(1536, unownedNativeCount(pool, 1536).orElseThrow());
                assertEquals(3, pool.connection.reads);
                assertEquals(Collections.nCopies(3, "EVAL"), pool.fallback.names());
                assertEquals(2, pool.borrows);
                assertEquals(1, pool.brokenReturns);
                assertEquals(1, pool.healthyReturns);
            }
        }
    }

    @Test void unownedNativeCountRejectionCannotHideMalformedLivenessOrKeyDenial() {
        for (int position : List.of(0, 1, 2)) {
            for (String invalid : List.of("status", "phase-size", "exec-size", "null-exec", "negative",
                    "overflow", "wrongtype", "key-acl", "exists-command-acl")) {
                try (FakePool pool = new FakePool()) {
                    for (int i = 0; i < 3; i++) {
                        var reply = i == (position + 1) % 3 ? unownedDeniedCount("MULTI") : exec(512L, 0L);
                        if (i == position) {
                            if (invalid.equals("status")) reply.set(0, bytes("not-OK"));
                            else if (invalid.equals("phase-size")) reply.removeLast();
                            else if (invalid.equals("null-exec")) reply.set(3, null);
                            else if (invalid.endsWith("acl")) {
                                reply.set(1, invalid.equals("key-acl")
                                        ? new JedisAccessControlException("NOPERM No permissions to access a key")
                                        : denied("exists"));
                                reply.set(3, new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
                            } else {
                                var values = new ArrayList<Object>((List<?>) reply.getLast());
                                if (invalid.equals("exec-size")) values.removeLast();
                                else values.set(0, switch (invalid) {
                                    case "negative" -> -1L;
                                    case "overflow" -> 513L;
                                    default -> new JedisDataException("WRONGTYPE liveness");
                                });
                                reply.set(3, values);
                            }
                        }
                        pool.connection.reply(reply);
                    }
                    var failure = assertThrows(RuntimeException.class, () -> unownedNativeCount(pool, 1536));
                    if (invalid.endsWith("acl")) assertInstanceOf(JedisAccessControlException.class, failure);
                    else if (invalid.equals("wrongtype")) assertEquals("WRONGTYPE liveness", failure.getMessage());
                    else assertInstanceOf(IllegalStateException.class, failure);
                    assertEquals(3, pool.connection.reads);
                    assertEquals(1, pool.borrows);
                    assertEquals(1, pool.brokenReturns);
                    assertFalse(pool.connection.names().contains("EVAL"));
                }
            }
        }
    }

    @Test void unownedNativeCountOversizedWireCohortDeclinesBeforeBorrow() {
        try (FakePool pool = new FakePool()) {
            String prefix = "x".repeat(840);
            int[] factories = {0};
            assertTrue(RedisStandaloneIndexSnapshot.indexCounts(pool, 10_000, start -> {
                factories[0]++;
                var window = unownedCountKeys(start, Math.min(512, 10_000 - start));
                return new RedisClientOperations.IndexReadKeys(
                        window.entities().stream().map(key -> prefix + key).toList(),
                        window.tombstones().stream().map(key -> prefix + key).toList(),
                        window.indexMetadata(), null);
            }, null).isEmpty());
            assertEquals(20, factories[0]);
            assertEquals(0, pool.borrows);
        }
    }

    @Test void unownedNativeCountSendReadFailureOrCancellationDiscardsWithoutFallback() {
        for (int mode = 0; mode < 4; mode++) {
            try (FakePool pool = new FakePool()) {
                pool.connection.failureAtSend = mode == 0 ? 6 : -1;
                pool.connection.failureAtRead = mode == 1 ? 2 : -1;
                pool.connection.interruptAtSend = mode == 2 ? 6 : -1;
                pool.connection.interruptAtRead = mode == 3 ? 1 : -1;
                for (int i = 0; i < 3; i++) pool.connection.reply(exec(512L, 0L));
                try {
                    var failure = assertThrows(RuntimeException.class, () -> unownedNativeCount(pool, 1536));
                    if (mode >= 2) assertInstanceOf(CancellationException.class, failure);
                    else assertInstanceOf(JedisConnectionException.class, failure);
                    assertEquals(1, pool.borrows);
                    assertEquals(1, pool.brokenReturns);
                    assertEquals(0, pool.healthyReturns);
                    assertFalse(pool.connection.names().contains("EVAL"));
                } finally { Thread.interrupted(); }
            }
        }
    }

    private static OptionalLong unownedNativeCount(FakePool pool, int size) {
        return RedisStandaloneIndexSnapshot.indexCounts(pool, size,
                start -> unownedCountKeys(start, Math.min(512, size - start)), null);
    }

    private static RedisClientOperations.IndexReadKeys unownedCountKeys(int start, int size) {
        var keys = countKeys(start, size);
        var metadata = new AbstractList<String>() {
            @Override public int size() { return size; }
            @Override public String get(int index) { throw new AssertionError("COUNT(*) must not resolve metadata keys"); }
        };
        return new RedisClientOperations.IndexReadKeys(keys.entities(), keys.tombstones(), metadata, null);
    }

    private static List<Object> unownedDeniedCount(String command) {
        var reply = exec(512L, 0L);
        if (command.equals("MULTI")) {
            reply.set(0, denied("multi")); reply.set(1, 512L); reply.set(2, 0L);
            reply.set(3, new JedisDataException("ERR EXEC without MULTI"));
        } else reply.set(3, denied("exec"));
        return reply;
    }

    private static OptionalLong nativeCount(FakePool pool, int size, String tenant) {
        return RedisStandaloneIndexSnapshot.indexCounts(pool, size,
                start -> countKeys(start, Math.min(512, size - start)), tenant);
    }

    private static RedisClientOperations.IndexReadKeys countKeys(int start, int size) {
        var ids = java.util.stream.IntStream.range(start, start + size);
        var entities = ids.mapToObj(id -> "entity:" + id).toList();
        var tombs = java.util.stream.IntStream.range(start, start + size).mapToObj(id -> "tomb:" + id).toList();
        var owners = java.util.stream.IntStream.range(start, start + size).mapToObj(id -> "owner:" + id).toList();
        return new RedisClientOperations.IndexReadKeys(entities, tombs, Collections.nCopies(size, null), owners);
    }

    private static List<Object> countExec(int size, String tenant) {
        var values = new ArrayList<Object>(); values.add((long) size); values.add(0L);
        for (int i = 0; i < size; i++) values.add(bytes(tenant));
        return exec(values.toArray());
    }

    private static List<Object> evalCount(long count) { return List.of(count, List.of(), List.of()); }

    private static List<Object> deniedCount(String command) {
        var replies = countExec(512, "a");
        if (command.equals("MULTI")) {
            replies.set(0, denied("multi"));
            replies.set(1, 512L); replies.set(2, 0L);
            for (int i = 3; i < replies.size() - 1; i++) replies.set(i, bytes("a"));
            replies.set(replies.size() - 1, new JedisDataException("ERR EXEC without MULTI"));
        } else if (command.equals("EXEC")) replies.set(replies.size() - 1, denied("exec"));
        else {
            replies.set(3, command.equals("OWNER_KEY")
                    ? new JedisAccessControlException("NOPERM No permissions to access a key") : denied("getrange"));
            replies.set(replies.size() - 1,
                    new JedisDataException("EXECABORT Transaction discarded because of previous errors."));
        }
        return replies;
    }

    private static JedisAccessControlException denied(String command) {
        return new JedisAccessControlException("NOPERM User test has no permissions to run the '" + command + "' command");
    }

    private static Optional<RedisClientOperations.IndexAggregateBatch> aggregate(
            FakePool pool, String tenant, String field, boolean count) {
        return RedisStandaloneIndexSnapshot.aggregate(pool, ENTITIES, TOMBS, META, OWNERS, tenant, field, count);
    }
    private static void owned(FakePool pool, String first, String second) {
        pool.connection.reply(List.of(bytes("OK"), 2L, 0L, bytes(first), bytes(second)));
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static List<Object> exec(Object... values) {
        var result = new ArrayList<Object>(); result.add(bytes("OK"));
        for (int i = 0; i < values.length; i++) result.add(bytes("QUEUED"));
        result.add(Arrays.asList(values));
        return result;
    }
    private static List<Object> lengths(Object... values) {
        var result = new ArrayList<Object>(Arrays.asList(values)); result.add(bytes("OK")); return result;
    }
    private static List<Object> metadataProof(String first, String second) {
        return List.of(2L, 0L, bytes(first), bytes(second));
    }
    private static void metadataExec(FakePool pool, List<Object> proof, List<?> sizes, Object... values) {
        pool.connection.reply(Collections.nCopies(proof.size(), bytes("QUEUED")));
        if (!sizes.isEmpty()) pool.connection.reply(Collections.nCopies(sizes.size(), bytes("QUEUED")));
        var committed = new ArrayList<Object>(proof);
        committed.addAll(sizes);
        committed.addAll(Arrays.asList(values));
        var last = new ArrayList<Object>(Collections.nCopies(values.length, bytes("QUEUED")));
        last.add(committed);
        pool.connection.reply(last);
    }
    private static List<Object> abort(int commands) {
        var result = new ArrayList<Object>();
        for (int i = 0; i < commands; i++) result.add(bytes("QUEUED"));
        result.add(null);
        return result;
    }

    static final class FakeConnection extends Connection {
        final Deque<List<Object>> replies = new ArrayDeque<>();
        final List<List<List<String>>> phases = new ArrayList<>();
        final List<Integer> readTimeouts = new ArrayList<>();
        final List<Integer> requestedReplies = new ArrayList<>();
        final List<List<String>> pending = new ArrayList<>();
        int sends, reads, failureAtSend = -1, failureAtRead = -1, disconnectAfterReads = -1;
        int interruptAtSend = -1, interruptAtRead = -1;
        @Override public boolean isConnected() { return disconnectAfterReads < 0 || reads < disconnectAfterReads; }
        @Override public void sendCommand(CommandArguments args) {
            assertTrue(args.getKeys().isEmpty(), "Standalone commands must not retain an unused routing registry");
            if (++sends == failureAtSend) throw new JedisConnectionException("Injected send failure");
            var command = new ArrayList<String>();
            for (var argument : args) command.add(new String(argument.getRaw(), StandardCharsets.UTF_8));
            pending.add(command);
            if (sends == interruptAtSend) Thread.currentThread().interrupt();
        }
        @Override public List<Object> getMany(int count) {
            requestedReplies.add(count);
            phases.add(List.copyOf(pending)); pending.clear(); readTimeouts.add(getSoTimeout());
            if (++reads == failureAtRead) throw new JedisConnectionException("Injected receive failure");
            if (reads == interruptAtRead) Thread.currentThread().interrupt();
            return replies.removeFirst();
        }
        void reply(List<Object> reply) { replies.add(reply); }
        List<String> names() { return phases.stream().flatMap(Collection::stream).map(c -> c.get(0)).toList(); }
    }

    static final class FakePool extends Pool<Connection> {
        final FakeConnection connection = new FakeConnection();
        final FakeConnection fallback = new FakeConnection();
        int borrows, healthyReturns, brokenReturns;
        boolean failReturn;
        Duration wait;
        FakePool() { super(new BasePooledObjectFactory<Connection>() {
            public Connection create() { throw new AssertionError("No socket factory expected"); }
            public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
        }, config()); }
        private static GenericObjectPoolConfig<Connection> config() {
            var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false); return config;
        }
        @Override public Connection borrowObject(Duration wait) {
            borrows++; this.wait = wait;
            return connection.isBroken() ? fallback : connection;
        }
        @Override public void returnResource(Connection connection) {
            assertTrue(connection == this.connection || connection == fallback); healthyReturns++;
            if (failReturn) throw new JedisConnectionException("Injected pool return failure");
        }
        @Override public void returnBrokenResource(Connection connection) {
            assertTrue(connection == this.connection || connection == fallback); brokenReturns++;
        }
    }
}
