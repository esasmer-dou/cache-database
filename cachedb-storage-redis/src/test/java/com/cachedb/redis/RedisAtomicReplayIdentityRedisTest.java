package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(30)
class RedisAtomicReplayIdentityRedisTest {
    @Test void byteIdenticalCrossWorkerReplaySurvivesEffectiveMemoryProfileDrift() {
        try (var f = new Fixture()) {
            var firstWorker = f.registry(16, 120);
            var secondWorker = f.registry(0, 5);
            var first = f.batch(firstWorker, "import").put(new RedisAtomicWriteBatchTest.Row(1L, "original"), 0).commit();
            String receiptKey = f.receiptKey("import");
            var receiptFields = f.redis.hgetAll(receiptKey);
            String payloadKey = f.keys.entityKey("rows", 1L);
            long payloadDeadline = f.deadline(payloadKey), receiptDeadline = f.deadline(receiptKey);
            assertTrue(payloadDeadline > 0);
            assertTrue(receiptDeadline > 0);
            var replay = assertDoesNotThrow(() -> f.batch(secondWorker, "import")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "original"), 0).commit());
            assertEquals(first, replay);
            assertEquals("1|original", f.redis.get(payloadKey));
            assertEquals("1", f.redis.get(f.keys.versionKey("rows", 1L)));
            assertEquals(1L, f.redis.xlen(f.config.activeStreamKey()));
            assertEquals(receiptFields, f.redis.hgetAll(receiptKey));
            assertEquals(payloadDeadline, f.deadline(payloadKey));
            assertEquals(receiptDeadline, f.deadline(receiptKey));
        }
    }

    @Test void hotLimitTtlAdmissionClockTracingAndInvalidationDriftRetainTheOriginalReceipt() {
        try (var f = new Fixture()) {
            var firstRegistry = f.registry(16, 120);
            var binding = firstRegistry.find("Row").orElseThrow();
            var base = new RedisAtomicWriteBatchTest();
            firstRegistry.register(base.metadata(), base.codec(), binding.cachePolicy().withNearCachePolicy(
                    new com.reactor.cachedb.core.cache.NearCachePolicy(16, 10, 4096)), null, null);
            var receipt = f.batch(firstRegistry, "profiles")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
            assertTrue(f.transport.arguments.contains("nearInvalidationChannel"));
            var fields = f.redis.hgetAll(f.receiptKey("profiles"));
            long deadline = f.deadline(f.receiptKey("profiles"));
            assertEquals(133, f.transport.arguments.getFirst().length());
            assertTrue(fields.get("fingerprint").matches("[0-9a-f]{64}"));
            assertTrue(fields.get("logicalFingerprint").matches("[0-9a-f]{64}"));
            assertEquals(legacyFingerprint(f.transport.keys, f.transport.arguments), fields.get("fingerprint"));
            for (int limit : new int[]{0, 1, 32}) {
                var batch = f.batch(f.registry(limit, 7), "profiles");
                batch.withTraceContextPropagator(new com.reactor.cachedb.core.trace.TraceContextPropagator() {
                    public Map<String, String> capture() { return Map.of("worker", "second"); }
                    public Scope restore(Map<String, String> metadata) { return () -> {}; }
                });
                batch.withNearInvalidationPublisher(() -> false).withQueryIndexConfig(
                        com.reactor.cachedb.core.config.QueryIndexConfig.builder()
                                .exactIndexEnabled(false).rangeIndexEnabled(false).build());
                assertEquals(receipt, batch.put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
                assertTrue(f.transport.arguments.contains("traceMetadata"));
                assertFalse(f.transport.arguments.contains("nearInvalidationChannel"));
            }
            assertEquals(fields, f.redis.hgetAll(f.receiptKey("profiles")));
            assertEquals(deadline, f.deadline(f.receiptKey("profiles")));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void oldCallerRetriesNewReceiptUsingTheUnchangedLegacyHash() {
        try (var f = new Fixture()) {
            var receipt = f.batch(f.registry(16, 120), "old-caller")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
            var fields = f.redis.hgetAll(f.receiptKey("old-caller"));
            var args = new ArrayList<>(f.transport.arguments);
            args.set(0, args.getFirst().substring(4, 68));
            assertEquals(legacyFingerprint(f.transport.keys, args), args.getFirst());
            assertEquals(List.of(receipt.streamEntryId(), "1"), f.redis.eval(f.transport.script, f.transport.keys, args));
            // The original script's replay branch reads ONLY the unchanged legacy fields.
            assertEquals(List.of(receipt.streamEntryId(), "1"), f.redis.eval("""
                    local prior=redis.call('HGET',KEYS[2],'fingerprint')
                    if prior~=ARGV[1] then return redis.error_reply('Batch id reused with different operations') end
                    return {redis.call('HGET',KEYS[2],'stream'),redis.call('HGET',KEYS[2],'v1')}
                    """, f.transport.keys, args));
            assertEquals(fields, f.redis.hgetAll(f.receiptKey("old-caller")));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void oldReceiptsRemainStrictLegacyWithoutUnverifiableBackfill() {
        try (var f = new Fixture()) {
            f.transport.legacyCaller = true;
            var receipt = f.batch(f.registry(16, 120), "old-record")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
            f.transport.legacyCaller = false;
            var fields = f.redis.hgetAll(f.receiptKey("old-record"));
            long deadline = f.deadline(f.receiptKey("old-record"));
            assertFalse(fields.containsKey("logicalFingerprint"));
            assertEquals(receipt, f.batch(f.registry(16, 120), "old-record")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            assertRejected(() -> f.batch(f.registry(0, 1), "old-record")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            assertEquals(fields, f.redis.hgetAll(f.receiptKey("old-record")));
            assertEquals(deadline, f.deadline(f.receiptKey("old-record")));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void changedPayloadIdOperationOrderAndExpectedVersionNeverReplay() {
        try (var f = new Fixture()) {
            var registry = f.registry(16, 120);
            f.batch(registry, "immutable").put(new RedisAtomicWriteBatchTest.Row(1L, "one"), 0)
                    .put(new RedisAtomicWriteBatchTest.Row(2L, "two"), 0).commit();
            var before = f.redis.hgetAll(f.receiptKey("immutable"));
            assertRejected(() -> f.batch(registry, "immutable").put(new RedisAtomicWriteBatchTest.Row(1L, "changed"), 0)
                    .put(new RedisAtomicWriteBatchTest.Row(2L, "two"), 0).commit());
            assertRejected(() -> f.batch(registry, "immutable").put(new RedisAtomicWriteBatchTest.Row(3L, "one"), 0)
                    .put(new RedisAtomicWriteBatchTest.Row(2L, "two"), 0).commit());
            assertRejected(() -> f.batch(registry, "immutable").delete(RedisAtomicWriteBatchTest.Row.class, 1L)
                    .put(new RedisAtomicWriteBatchTest.Row(2L, "two"), 0).commit());
            assertRejected(() -> f.batch(registry, "immutable").put(new RedisAtomicWriteBatchTest.Row(2L, "two"), 0)
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "one"), 0).commit());
            assertRejected(() -> f.batch(registry, "immutable").put(new RedisAtomicWriteBatchTest.Row(1L, "one"), 1)
                    .put(new RedisAtomicWriteBatchTest.Row(2L, "two"), 0).commit());
            assertEquals(before, f.redis.hgetAll(f.receiptKey("immutable")));
            assertEquals("1|one", f.redis.get(f.keys.entityKey("rows", 1L)));
            assertEquals("2|two", f.redis.get(f.keys.entityKey("rows", 2L)));
            assertFalse(f.redis.exists(f.keys.entityKey("rows", 3L)));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void logicalIdentityBindsEveryMappingFieldAndTheActualPhysicalKeys() {
        try (var f = new Fixture()) {
            f.batch(f.registry(16, 120), "mapping").put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
            var before = f.redis.hgetAll(f.receiptKey("mapping"));
            for (String field : List.of("entity", "table", "namespace", "id", "version", "deleted", "active", "marker")) {
                var registry = f.registry(new Mapping(field, null), new RedisAtomicWriteBatchTest().codec(), 16, 120);
                assertRejected(() -> f.batch(registry, "mapping").put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            }
            var otherKeys = new RedisKeyStrategy(f.prefix + ":different") {
                @Override public String writeStreamKey(String stream, String namespace) { return f.config.activeStreamKey(); }
            };
            assertRejected(() -> new RedisAtomicWriteBatch(f.transport, f.registry(16, 120), otherKeys, f.config,
                    RedisGuardrailConfig.defaults(), null, "mapping").put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            assertEquals(before, f.redis.hgetAll(f.receiptKey("mapping")));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void tenantChangesAreRejectedEvenWhenTheCurrentOwnerAllowsTheDifferentTenant() {
        try (var f = new Fixture()) {
            String owner = f.keys.versionKey("rows", 1L) + ":tenant";
            f.redis.set(owner, "a");
            try (var tenant = CacheTenantContext.withTenant("a")) {
                f.batch(f.registry(new Mapping("", "a"), new RedisAtomicWriteBatchTest().codec(), 16, 120), "tenant")
                        .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
            }
            f.redis.set(owner, "b");
            try (var tenant = CacheTenantContext.withTenant("b")) {
                assertRejected(() -> f.batch(f.registry(new Mapping("", "b"), new RedisAtomicWriteBatchTest().codec(), 16, 120), "tenant")
                        .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            }
            assertEquals("b", f.redis.get(owner));
            assertEquals("1", f.redis.get(f.keys.versionKey("rows", 1L)));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void malformedCompoundAndCorruptLogicalMetadataFailClosedWithoutRewritingReceipts() {
        try (var f = new Fixture()) {
            f.batch(f.registry(16, 120), "malformed").put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
            var before = f.redis.hgetAll(f.receiptKey("malformed"));
            var args = new ArrayList<>(f.transport.arguments);
            String compound = args.getFirst();
            for (String invalid : List.of("", "~L2:", compound.substring(0, 132), compound + "0",
                    "~L2:" + "G".repeat(64) + ":" + "a".repeat(64), "~L2:" + "a".repeat(64) + ":" + "z".repeat(64))) {
                args.set(0, invalid);
                var error = assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                        () -> f.redis.eval(f.transport.script, f.transport.keys, args));
                assertFalse(error.getMessage().contains("|value"));
                assertFalse(error.getMessage().contains(compound));
            }
            args.set(0, compound);
            for (String invalid : List.of("", "a".repeat(63), "a".repeat(65), "x".repeat(64), "0".repeat(64))) {
                f.redis.hset(f.receiptKey("malformed"), "logicalFingerprint", invalid);
                var corrupt = f.redis.hgetAll(f.receiptKey("malformed"));
                assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                        () -> f.redis.eval(f.transport.script, f.transport.keys, args));
                assertEquals(corrupt, f.redis.hgetAll(f.receiptKey("malformed")));
            }
            f.redis.hset(f.receiptKey("malformed"), "logicalFingerprint", before.get("logicalFingerprint"));
            f.redis.hset(f.receiptKey("malformed"), "fingerprint", "a".repeat(65));
            assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                    () -> f.redis.eval(f.transport.script, f.transport.keys, args));
            f.redis.hdel(f.receiptKey("malformed"), "fingerprint");
            assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                    () -> f.redis.eval(f.transport.script, f.transport.keys, args));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
            assertEquals("1", f.redis.get(f.keys.versionKey("rows", 1L)));
        }
    }

    @Test void corruptReplayStreamMetadataIsBoundedAndRejectedWithoutMutation() {
        try (var f = new Fixture()) {
            var registry = f.registry(16, 120);
            var original = f.batch(registry, "stream-metadata")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
            String receipt = f.receiptKey("stream-metadata");
            long deadline = f.deadline(receipt);
            for (String invalid : List.of("", "1", "x-y", "0-0", "01-0", "1-01",
                    "18446744073709551616-0", "1-18446744073709551616", "x".repeat(1024 * 1024))) {
                f.redis.hset(receipt, "stream", invalid);
                var before = f.redis.hgetAll(receipt);
                var failure = assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                        () -> f.batch(registry, "stream-metadata")
                                .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
                assertEquals("Invalid batch replay identity", failure.getMessage());
                assertEquals(before, f.redis.hgetAll(receipt));
                assertEquals(deadline, f.deadline(receipt));
            }
            f.redis.hset(receipt, "stream", original.streamEntryId());
            assertEquals(original, f.batch(registry, "stream-metadata")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
            assertEquals("1", f.redis.get(f.keys.versionKey("rows", 1L)));
        }
    }

    @Test void corruptReplayVersionsAndMemberCountsFailBeforeReturningAReceipt() {
        try (var f = new Fixture()) {
            var registry = f.registry(16, 120);
            var original = f.batch(registry, "version-metadata")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
            String receipt = f.receiptKey("version-metadata");
            long deadline = f.deadline(receipt);
            for (String invalid : List.of("", "0", "-1", "01", "1.5", "1e+14", "NaN",
                    "9007199254740992", "9999999999999999", "9".repeat(1024 * 1024))) {
                f.redis.hset(receipt, "v1", invalid);
                var before = f.redis.hgetAll(receipt);
                assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                        () -> f.batch(registry, "version-metadata")
                                .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
                assertEquals(before, f.redis.hgetAll(receipt));
                assertEquals(deadline, f.deadline(receipt));
            }
            f.redis.hdel(receipt, "v1");
            assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                    () -> f.batch(registry, "version-metadata")
                            .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            f.redis.hset(receipt, "v1", "1");
            f.redis.hset(receipt, "v2", "1");
            assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                    () -> f.batch(registry, "version-metadata")
                            .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            f.redis.hdel(receipt, "v2");
            f.redis.hset(receipt, "v999", "1");
            assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                    () -> f.batch(registry, "version-metadata")
                            .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            f.redis.hdel(receipt, "v999");
            assertEquals(original, f.batch(registry, "version-metadata")
                    .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
            assertEquals("1|value", f.redis.get(f.keys.entityKey("rows", 1L)));
        }
    }

    @Test void highSafeVersionsRemainExactDecimalOnFirstAcceptanceAndReplay() {
        try (var f = new Fixture()) {
            var registry = f.registry(16, 120);
            for (long current : new long[]{99_999_999_999_999L, 9_007_199_254_740_989L}) {
                String batchId = "high-" + current;
                f.redis.set(f.keys.versionKey("rows", 1L), Long.toString(current));
                var original = f.batch(registry, batchId)
                        .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), current).commit();
                assertEquals(List.of(current + 1), original.versions());
                assertEquals(Long.toString(current + 1), f.redis.hget(f.receiptKey(batchId), "v1"));
                assertEquals(original, f.batch(registry, batchId)
                        .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), current).commit());
            }
            var exhausted = assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                    () -> f.batch(registry, "exhausted").put(new RedisAtomicWriteBatchTest.Row(1L, "no"),
                            9_007_199_254_740_990L).commit());
            assertEquals("Entity version exhausted", exhausted.getMessage());
            assertFalse(f.redis.exists(f.receiptKey("exhausted")));
            assertEquals(2, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void malformedTransportRepliesNeverBecomeAcceptedReceipts() {
        try (var f = new Fixture()) {
            var registry = f.registry(16, 120);
            f.transport.overrideReply = true;
            for (Object invalid : Arrays.asList(null, "not-a-list", List.of(), List.of("1-0"),
                    List.of("1-0", "1", "2"), List.of("wrong", "1"), List.of("0-0", "1"),
                    List.of("1-0", "0"), List.of("1-0", "01"), List.of("1-0", "9007199254740992"),
                    List.of("1-0", 1.0d), List.of("1-0", new java.math.BigDecimal("1.5")),
                    Arrays.asList(null, "1"), Arrays.asList("1-0", null))) {
                f.transport.reply = invalid;
                var failure = assertThrows(IllegalStateException.class, () -> f.batch(registry, "bad-reply")
                        .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
                assertEquals("Invalid batch receipt response", failure.getMessage());
            }
            assertFalse(f.redis.exists(f.receiptKey("bad-reply")));
            assertFalse(f.redis.exists(f.keys.versionKey("rows", 1L)));
            assertEquals(0, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void knownExactAdapterScalarFormsRetainReceiptCompatibility() {
        try (var f = new Fixture()) {
            var registry = f.registry(16, 120);
            f.transport.overrideReply = true;
            for (Object stream : List.of("1-0", new redis.clients.jedis.StreamEntryID("1-0"))) {
                for (Object version : List.of("1", (byte) 1, (short) 1, 1, 1L)) {
                    f.transport.reply = List.of(stream, version);
                    var receipt = f.batch(registry, "exact-scalars")
                            .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
                    assertEquals("1-0", receipt.streamEntryId());
                    assertEquals(List.of(1L), receipt.versions());
                }
            }
            assertFalse(f.redis.exists(f.receiptKey("exact-scalars")));
        }
    }

    @Test void patchReplayIgnoresOnlyItsDerivedHotCasAndMergedState() {
        try (var f = new Fixture()) {
            var registry = f.registry(new Mapping("", null), new Codec(false, false, ""), 16, 120);
            f.batch(registry, "seed").put(new RedisAtomicWriteBatchTest.Row(1L, "before"), 0).commit();
            var first = f.batch(registry, "patch").patch(RedisAtomicWriteBatchTest.Row.class, 1L, Map.of("value", "after")).commit();
            assertEquals(List.of(2L), first.versions());
            var fields = f.redis.hgetAll(f.receiptKey("patch"));
            long deadline = f.deadline(f.keys.entityKey("rows", 1L));
            // This hot read observes version 2 instead of the first attempt's version 1.
            assertEquals(first, f.batch(registry, "patch")
                    .patch(RedisAtomicWriteBatchTest.Row.class, 1L, Map.of("value", "after")).commit());
            assertRejected(() -> f.batch(registry, "patch")
                    .patch(RedisAtomicWriteBatchTest.Row.class, 1L, Map.of("value", "different")).commit());
            assertEquals(fields, f.redis.hgetAll(f.receiptKey("patch")));
            assertEquals("2", f.redis.get(f.keys.versionKey("rows", 1L)));
            assertEquals("1|after", f.redis.get(f.keys.entityKey("rows", 1L)));
            assertEquals(deadline, f.deadline(f.keys.entityKey("rows", 1L)));
            assertEquals(2, f.redis.xlen(f.config.activeStreamKey()));
            // A separate first acceptance still checks the captured CAS at execution.
            var stale = f.batch(registry, "stale-patch").patch(RedisAtomicWriteBatchTest.Row.class, 1L, Map.of("value", "stale"));
            f.batch(registry, "advance").put(new RedisAtomicWriteBatchTest.Row(1L, "current"), 2).commit();
            var conflict = assertThrows(redis.clients.jedis.exceptions.JedisDataException.class, stale::commit);
            assertEquals("Batch optimistic version conflict", conflict.getMessage());
            assertEquals("3", f.redis.get(f.keys.versionKey("rows", 1L)));
            assertFalse(f.redis.exists(f.receiptKey("stale-patch")));
        }
    }

    @Test void sortedColumnsAndProjectionsReplayButChangedProjectionOrRedisPayloadDoesNot() {
        try (var f = new Fixture()) {
            var one = f.registry(new Mapping("", null), new Codec(false, false, ""), 16, 120);
            var two = f.registry(new Mapping("", null), new Codec(true, false, ""), 16, 120);
            f.projections(one, List.of("a", "b"), ""); f.projections(two, List.of("b", "a"), "");
            var first = f.batch(one, "sorted").put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
            assertEquals(first, f.batch(two, "sorted").put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            var changedProjection = f.registry(new Mapping("", null), new Codec(false, false, ""), 16, 120);
            f.projections(changedProjection, List.of("a", "b"), "changed");
            assertRejected(() -> f.batch(changedProjection, "sorted").put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            var changedPayload = f.registry(new Mapping("", null), new Codec(false, false, "changed"), 16, 120);
            f.projections(changedPayload, List.of("a", "b"), "");
            assertRejected(() -> f.batch(changedPayload, "sorted").put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit());
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void nullEmptyAndEmbeddedNulRemainDistinctWithIdenticalPayloads() {
        try (var f = new Fixture()) {
            var registry = f.registry(new Mapping("", null), new Codec(false, true, ""), 16, 120);
            f.batch(registry, "framed").put(new RedisAtomicWriteBatchTest.Row(1L, null), 0).commit();
            for (String changed : List.of("", "null", "\0", "x\0value\0y", "x\0\0y"))
                assertRejected(() -> f.batch(registry, "framed").put(new RedisAtomicWriteBatchTest.Row(1L, changed), 0).commit());
            assertEquals("constant", f.redis.get(f.keys.entityKey("rows", 1L)));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void filledCompoundIdentityCountsTowardTheFourMiBWireBudget() {
        try (var f = new Fixture()) {
            var registry = f.registry(16, 120);
            f.batch(registry, "sizing1").put(new RedisAtomicWriteBatchTest.Row(1L, ""), 0).commit();
            long overhead = f.transport.arguments.stream().mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();
            // The value is encoded once in the payload and once in the queued columns.
            // Stay inside the 133-byte identity gap despite Instant's variable fractional digits.
            int length = Math.toIntExact((4 * 1024 * 1024L - overhead - 64) / 2);
            f.batch(registry, "sizing2").put(new RedisAtomicWriteBatchTest.Row(2L, "x".repeat(length)), 0).commit();
            long actual = f.transport.arguments.stream().mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();
            assertTrue(actual <= 4 * 1024 * 1024L);
            int overflowLength = Math.toIntExact((4 * 1024 * 1024L - overhead + 64) / 2);
            assertTrue(overhead + 2L * overflowLength > 4 * 1024 * 1024L);
            assertTrue(overhead + 2L * overflowLength - 133 < 4 * 1024 * 1024L);
            var failure = assertThrows(IllegalArgumentException.class, () -> f.batch(registry, "sizing3")
                    .put(new RedisAtomicWriteBatchTest.Row(3L, "x".repeat(overflowLength)), 0).commit());
            assertEquals("Atomic batch exceeds 4 MiB", failure.getMessage());
            assertFalse(f.redis.exists(f.keys.entityKey("rows", 3L)));
            assertFalse(f.redis.exists(f.receiptKey("sizing3")));
        }
    }

    @Test void oneHundredItemsStillSucceedAndTheNextItemFailsBeforeAnyMutation() {
        try (var f = new Fixture()) {
            var registry = f.registry(128, 0);
            var accepted = f.batch(registry, "hundred");
            for (long id = 1; id <= 100; id++) accepted.put(new RedisAtomicWriteBatchTest.Row(id, "value"), 0);
            var receipt = accepted.commit();
            assertEquals(Collections.nCopies(100, 1L), receipt.versions());
            var rejected = f.batch(registry, "overflow");
            for (long id = 101; id <= 200; id++) rejected.put(new RedisAtomicWriteBatchTest.Row(id, "value"), 0);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> rejected.put(new RedisAtomicWriteBatchTest.Row(201L, "value"), 0));
            assertEquals("Atomic batch exceeds 100 operations", failure.getMessage());
            assertFalse(f.redis.exists(f.keys.entityKey("rows", 101L)));
            assertFalse(f.redis.exists(f.receiptKey("overflow")));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    @Test void separateJvmsReplayImmutableMapsAndReversedProjectionRegistration() throws Exception {
        try (var f = new Fixture()) {
            worker(f.prefix, "first");
            var before = f.redis.hgetAll(f.receiptKey("cross-jvm"));
            long payloadDeadline = f.deadline(f.keys.entityKey("rows", 1L));
            long receiptDeadline = f.deadline(f.receiptKey("cross-jvm"));
            worker(f.prefix, "second");
            assertEquals(before, f.redis.hgetAll(f.receiptKey("cross-jvm")));
            assertEquals(payloadDeadline, f.deadline(f.keys.entityKey("rows", 1L)));
            assertEquals(receiptDeadline, f.deadline(f.receiptKey("cross-jvm")));
            assertEquals("1", f.redis.get(f.keys.versionKey("rows", 1L)));
            assertEquals(1, f.redis.xlen(f.config.activeStreamKey()));
        }
    }

    private static void worker(String prefix, String mode) throws Exception {
        String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(executable, "-Xmx128m", "-XX:ActiveProcessorCount=1",
                "-Dcachedb.snapshot.redis.host=" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                "-Dcachedb.snapshot.redis.port=" + Integer.getInteger("cachedb.snapshot.redis.port", 16383),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Worker.class.getName(), prefix, mode).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "Replay worker deadline exceeded");
            byte[] output = process.getInputStream().readNBytes(8193);
            assertTrue(output.length <= 8192, "Replay worker output limit exceeded");
            assertEquals(0, process.exitValue(), () -> new String(output, StandardCharsets.UTF_8));
            assertTrue(new String(output, StandardCharsets.UTF_8).contains("REPLAY_WORKER_OK"));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); }
        }
    }

    /** Launched only by the K8s test; each process gets its own immutable-map iteration seed. */
    public static final class Worker {
        public static void main(String[] args) {
            String prefix = args[0]; boolean second = args[1].equals("second");
            var keys = new RedisKeyStrategy(prefix);
            var config = WriteBehindConfig.builder().streamKey(prefix + ":stream").build();
            var base = new RedisAtomicWriteBatchTest();
            var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
            registry.register(base.metadata(), base.codec(), CachePolicy.builder().hotEntityLimit(16).entityTtlSeconds(120).build(), null, null);
            Fixture.projections(registry, second ? List.of("b", "a") : List.of("a", "b"), "");
            try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
                var transport = new Capture(redis);
                var receipt = new RedisAtomicWriteBatch(transport, registry, keys, config, RedisGuardrailConfig.defaults(), null, "cross-jvm")
                        .put(new RedisAtomicWriteBatchTest.Row(1L, "value"), 0).commit();
                assertEquals(List.of(1L), receipt.versions());
                String stored = redis.hget(config.activeStreamKey() + ":batch:cross-jvm", "fingerprint");
                String incoming = transport.arguments.getFirst().substring(4, 68);
                if (second) assertNotEquals(stored, incoming, "Reversed internal projection order must exercise the legacy mismatch");
                else assertEquals(stored, incoming);
                System.out.println("REPLAY_WORKER_OK");
            }
        }
    }

    private record Codec(boolean reverse, boolean constantPayload, String payloadSuffix) implements EntityCodec<RedisAtomicWriteBatchTest.Row> {
        public String toRedisValue(RedisAtomicWriteBatchTest.Row row) {
            return constantPayload ? "constant" : row.id() + "|" + row.value() + payloadSuffix;
        }
        public RedisAtomicWriteBatchTest.Row fromRedisValue(String value) {
            var parts = value.split("\\|", 2);
            return new RedisAtomicWriteBatchTest.Row(Long.valueOf(parts[0]), parts[1]);
        }
        public Map<String, Object> toColumns(RedisAtomicWriteBatchTest.Row row) {
            var result = new LinkedHashMap<String, Object>();
            if (reverse) { result.put("value", row.value()); result.put("id", row.id()); }
            else { result.put("id", row.id()); result.put("value", row.value()); }
            return result;
        }
        public RedisAtomicWriteBatchTest.Row fromColumns(Map<String, Object> columns) {
            return new RedisAtomicWriteBatchTest.Row(Long.valueOf(columns.get("id").toString()), (String) columns.get("value"));
        }
    }

    private static void assertRejected(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(redis.clients.jedis.exceptions.JedisDataException.class, action);
        assertEquals("Batch id reused with different operations", failure.getMessage());
    }

    private static String legacyFingerprint(List<String> keys, List<String> args) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String key : keys) { hash.update(key.getBytes(StandardCharsets.UTF_8)); hash.update((byte)0); }
            int views = 4 + Integer.parseInt(args.get(1)) * 11;
            int fields = views + 1 + Integer.parseInt(args.get(views)) * 6;
            for (int i = 1; i < args.size(); i++) {
                if (i >= fields && (i - fields) % 2 == 0 && (args.get(i).equals("traceMetadata")
                        || args.get(i).endsWith(":traceMetadata") || args.get(i).equals("nearInvalidationChannel")
                        || args.get(i).endsWith(":nearInvalidation"))) { i++; continue; }
                if (i == 3 || args.get(i - 1).endsWith("createdAt")) continue;
                hash.update(args.get(i).getBytes(StandardCharsets.UTF_8)); hash.update((byte)0);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private record Mapping(String changed, String tenant) implements EntityMetadata<RedisAtomicWriteBatchTest.Row, Long> {
        public String entityName() { return changed.equals("entity") ? "Other" : "Row"; }
        public String tableName() { return changed.equals("table") ? "other_rows" : "rows"; }
        public String redisNamespace() { return changed.equals("namespace") ? "other" : "rows"; }
        public String idColumn() { return changed.equals("id") ? "other_id" : "id"; }
        public String versionColumn() { return changed.equals("version") ? "other_version" : "entity_version"; }
        public String deletedColumn() { return changed.equals("deleted") ? "deleted" : null; }
        public String activeMarkerValue() { return changed.equals("active") ? "active" : "false"; }
        public String deletedMarkerValue() { return changed.equals("marker") ? "deleted" : "true"; }
        public String tenantFieldName() { return tenant == null ? null : "tenant"; }
        public String extractTenantId(RedisAtomicWriteBatchTest.Row row) { return tenant; }
        public Class<RedisAtomicWriteBatchTest.Row> entityType() { return RedisAtomicWriteBatchTest.Row.class; }
        public java.util.function.Function<RedisAtomicWriteBatchTest.Row, Long> idAccessor() { return RedisAtomicWriteBatchTest.Row::id; }
        public List<String> columns() { return List.of("id", "value"); }
        public List<RelationDefinition> relations() { return List.of(); }
    }

    private static final class Fixture implements AutoCloseable {
        final String prefix = "replay-identity-" + UUID.randomUUID();
        final RedisKeyStrategy keys = new RedisKeyStrategy(prefix);
        final WriteBehindConfig config = WriteBehindConfig.builder().streamKey(prefix + ":stream").build();
        final JedisPooled redis = new JedisPooled(RedisTestEndpoint.uri());
        final Capture transport = new Capture(redis);
        DefaultEntityRegistry registry(int hotLimit, long ttl) {
            var fixture = new RedisAtomicWriteBatchTest();
            return registry(fixture.metadata(), fixture.codec(), hotLimit, ttl);
        }
        DefaultEntityRegistry registry(EntityMetadata<RedisAtomicWriteBatchTest.Row, Long> metadata,
                EntityCodec<RedisAtomicWriteBatchTest.Row> codec, int hotLimit, long ttl) {
            var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
            registry.register(metadata, codec, CachePolicy.builder()
                    .hotEntityLimit(hotLimit).entityTtlSeconds(ttl).build(), null, null);
            return registry;
        }
        RedisAtomicWriteBatch batch(DefaultEntityRegistry registry, String id) {
            return new RedisAtomicWriteBatch(transport, registry, keys, config, RedisGuardrailConfig.defaults(), null, id);
        }
        static void projections(DefaultEntityRegistry registry, List<String> names, String suffix) {
            var codec = new com.reactor.cachedb.core.projection.ProjectionCodec<RedisAtomicWriteBatchTest.Row>() {
                public String toRedisValue(RedisAtomicWriteBatchTest.Row row) { return row.id() + "|" + row.value() + suffix; }
                public RedisAtomicWriteBatchTest.Row fromRedisValue(String value) { throw new UnsupportedOperationException(); }
            };
            for (String name : names) registry.registerProjection(new RedisAtomicWriteBatchTest().metadata(),
                    com.reactor.cachedb.core.projection.EntityProjection.of(name, codec, RedisAtomicWriteBatchTest.Row::id,
                            List.of("id", "value"), row -> Map.of("id", row.id(), "value", row.value()), row -> row));
        }
        String receiptKey(String id) { return config.activeStreamKey() + ":batch:" + id; }
        long deadline(String key) {
            return ((Number)redis.eval("return redis.call('PEXPIRETIME',KEYS[1])", List.of(key), List.of())).longValue();
        }
        public void close() {
            try {
                String cursor = "0";
                do {
                    var page = redis.scan(cursor, new ScanParams().match(prefix + ":*").count(100));
                    cursor = page.getCursor();
                    if (!page.getResult().isEmpty()) redis.unlink(page.getResult().toArray(String[]::new));
                } while (!cursor.equals("0"));
            } finally { redis.close(); }
        }
    }

    /** Captures only this batch's EVAL, never a global Redis command counter. */
    private static final class Capture implements RedisClientOperations {
        final JedisPooled redis;
        String script;
        List<String> keys, arguments;
        boolean legacyCaller;
        boolean overrideReply;
        Object reply;
        Capture(JedisPooled redis) { this.redis = redis; }
        public UnifiedJedis commands() { return redis; }
        public Object eval(String script, List<String> keys, List<String> arguments) {
            this.script = script; this.keys = List.copyOf(keys); this.arguments = List.copyOf(arguments);
            if (overrideReply) return reply;
            if (!legacyCaller) return redis.eval(script, keys, arguments);
            var legacy = new ArrayList<>(arguments);
            if (legacy.getFirst().startsWith("~L2:")) legacy.set(0, legacy.getFirst().substring(4, 68));
            return redis.eval(script, keys, legacy);
        }
        public void close() { /* The fixture owns the client. */ }
    }
}
