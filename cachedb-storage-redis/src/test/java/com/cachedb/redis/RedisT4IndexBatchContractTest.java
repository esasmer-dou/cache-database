package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

class RedisT4IndexBatchContractTest {
    private static final List<String> ENTITIES = List.of("{t4}:entity:1", "{t4}:entity:2");
    private static final List<String> TOMBSTONES = List.of("{t4}:tomb:1", "{t4}:tomb:2");
    private static final List<String> METADATA = List.of("{t4}:meta:1", "{t4}:meta:2");
    private static final List<String> OWNERS = List.of("{t4}:owner:1", "{t4}:owner:2");

    private static final class Client implements RedisClientOperations {
        Object reply = List.of(0L, List.of(), List.of());
        List<String> keys, arguments;
        String script;
        int evaluations;
        boolean cluster;
        public UnifiedJedis commands() { throw new AssertionError("No transport expected"); }
        public boolean clustered() { return cluster; }
        public Object eval(String script, List<String> keys, List<String> arguments) {
            requireSameSlot(keys);
            this.script = script; this.keys = List.copyOf(keys); this.arguments = List.copyOf(arguments);
            evaluations++;
            return reply;
        }
    }

    @Test void legacyOverloadAndTenantOverloadKeepTheirKeyAndArgumentLayouts() {
        var client = new Client();
        assertEquals(0, client.aggregateIndexBatch(ENTITIES, TOMBSTONES, METADATA, null, true).orElseThrow().count());
        assertEquals(List.of("2", "0", ""), client.arguments.subList(2, 5));
        assertEquals(4, client.keys.size());
        client.aggregateIndexBatch(ENTITIES, TOMBSTONES, METADATA, OWNERS, "tenant-a", "amount", false);
        var expected = new ArrayList<String>();
        expected.addAll(ENTITIES); expected.addAll(TOMBSTONES); expected.addAll(METADATA); expected.addAll(OWNERS);
        assertEquals(expected, client.keys);
        assertEquals(List.of("amount", "0", "2", "1", "tenant-a"), client.arguments);
        assertTrue(client.script.indexOf("'HSTRLEN'") < client.script.indexOf("'HGET'"));
        client.aggregateIndexBatch(ENTITIES, TOMBSTONES, METADATA, OWNERS, "tenant-a", null, true);
        assertEquals(6, client.keys.size());
        assertEquals(OWNERS, client.keys.subList(4, 6));
    }

    @Test void rejectsUnalignedOwnersInvalidFieldsAndOverBudgetKeysBeforeEval() {
        var client = new Client();
        assertThrows(IllegalArgumentException.class, () -> client.aggregateIndexBatch(
                List.of(), List.of(), List.of(), null, true));
        for (int size : new int[]{0, 513}) {
            var keys = Collections.nCopies(size, "{t4}:key");
            assertThrows(IllegalArgumentException.class, () -> client.facetIndexBatch(keys, keys, keys, null, null, "group", null));
        }
        assertThrows(IllegalArgumentException.class, () -> client.aggregateIndexBatch(
                ENTITIES, TOMBSTONES, METADATA, List.of("owner"), "a", null, true));
        assertThrows(IllegalArgumentException.class, () -> client.aggregateIndexBatch(
                ENTITIES, TOMBSTONES, METADATA, OWNERS, null, null, true));
        assertThrows(IllegalArgumentException.class, () -> client.aggregateIndexBatch(
                ENTITIES, TOMBSTONES, METADATA, null, "a", null, true));
        assertThrows(IllegalArgumentException.class, () -> client.aggregateIndexBatch(
                ENTITIES, TOMBSTONES, METADATA, OWNERS, " ", null, true));
        assertThrows(IllegalArgumentException.class, () -> client.facetIndexBatch(
                ENTITIES, TOMBSTONES, METADATA, null, null, "x".repeat(4097), null));
        assertThrows(IllegalArgumentException.class, () -> client.facetIndexBatch(
                ENTITIES, TOMBSTONES, METADATA, null, null, "group", ""));
        assertThrows(IllegalArgumentException.class, () -> client.aggregateIndexBatch(
                ENTITIES, TOMBSTONES, METADATA, null, false));
        var large = Collections.nCopies(512, "\u4e00".repeat(6000));
        assertThrows(IllegalArgumentException.class, () -> client.aggregateIndexBatch(large, large, large, null, true));
        var badKey = Arrays.asList("{t4}:key", (String) null);
        assertThrows(IllegalArgumentException.class, () -> client.aggregateIndexBatch(badKey, TOMBSTONES, METADATA, null, true));
        assertEquals(0, client.evaluations);
        var max = Collections.nCopies(512, "{t4}:key");
        assertEquals(0, client.aggregateIndexBatch(max, max, max, null, true).orElseThrow().count());
        assertEquals(1, client.evaluations);
    }

    @Test void ownerKeysParticipateInClusterSlotAdmissionForAllScripts() {
        var client = new Client(); client.cluster = true;
        var foreignSlot = List.of("{other}:owner:1", "{other}:owner:2");
        assertTrue(client.aggregateIndexBatch(ENTITIES, TOMBSTONES, METADATA, foreignSlot, "a", null, true).isEmpty());
        assertTrue(client.facetIndexBatch(ENTITIES, TOMBSTONES, METADATA, foreignSlot, "a", "group", null).isEmpty());
        assertTrue(client.liveIndexPayloadBatch(ENTITIES, TOMBSTONES, foreignSlot, "a").isEmpty());
        assertEquals(0, client.evaluations);
    }

    @Test void countOwnerGetrangeChecksExactAclOnlyAfterFailedBoundedRead() {
        var command = RedisClientOperations.indexAggregateCommand(ENTITIES, TOMBSTONES, METADATA, OWNERS,
                "tenant-a", null, true);
        String script = command.script();
        int tenant = script.indexOf("local tenant=ARGV[5]; local tenantLength=#tenant");
        int loop = script.indexOf("for i=1,n do", tenant);
        int read = script.indexOf("redis.pcall('GETRANGE',key,0,tenantLength)");
        int equality = script.indexOf("allowed=observed==tenant");
        int failed = script.indexOf("if not allowed and type(observed)=='table' and observed.err then");
        int permission = script.indexOf("redis.acl_check_cmd('GETRANGE',key,0,tenantLength)");
        int fallback = script.indexOf("allowed=redis.call('STRLEN',key)==tenantLength and redis.call('GET',key)==tenant");
        assertTrue(tenant >= 0 && tenant < loop && loop < read && read < equality && equality < failed
                && failed < permission && permission < fallback);
        assertTrue(script.substring(failed, fallback).contains("if not redis.acl_check_cmd or "));
        assertTrue(script.substring(permission, fallback).contains("return observed"));
        assertFalse(script.contains("redis.pcall('MGET'"));
        assertEquals(List.of("tenant-a"), command.arguments().subList(4, 5));
    }

    @Test void facetOwnerMgetCheckAclOnlyAfterFailedPcall() {
        var facet = RedisClientOperations.facetIndexCommand(ENTITIES, TOMBSTONES, METADATA, OWNERS,
                "tenant-a", "group", null, 1234, true);
        for (var command : List.of(facet)) {
            String script = command.script();
            int length = script.indexOf("redis.call('STRLEN'");
            int capability = script.indexOf("if redis.acl_check_cmd then");
            int read = script.indexOf("redis.pcall('MGET'");
            int failed = script.indexOf("if observed.err then");
            int permission = script.indexOf("if redis.acl_check_cmd('MGET'");
            int fallback = script.indexOf("redis.call('GET'");
            assertTrue(length >= 0 && length < capability && capability < read && read < failed
                    && failed < permission && permission < fallback);
            assertEquals(permission, script.lastIndexOf("if redis.acl_check_cmd('MGET'"));
            assertTrue(script.substring(permission, fallback).contains("then return observed end"));
            assertEquals(List.of("tenant-a"), command.arguments().subList(4, 5));
        }
    }

    @Test void countLivenessUsesDeclaredKeyRangesWithoutScratchCopiesAtBoundaries() {
        for (int size : new int[]{1, 512}) {
            var entities = Collections.nCopies(size, "{t4}:entity");
            var tombstones = Collections.nCopies(size, "{t4}:tomb");
            var metadata = Collections.nCopies(size, (String) null);
            var owners = Collections.nCopies(size, "{t4}:owner");
            for (boolean owned : List.of(false, true)) {
                var command = RedisClientOperations.indexAggregateCommand(entities, tombstones, metadata,
                        owned ? owners : null, owned ? "a" : null, null, true);
                var expected = new ArrayList<String>();
                expected.addAll(entities); expected.addAll(tombstones);
                if (owned) expected.addAll(owners);
                assertEquals(expected, command.keys());
                assertEquals(List.of("", "1", Integer.toString(size), owned ? "1" : "0", owned ? "a" : ""),
                        command.arguments());
                String script = command.script();
                assertTrue(script.contains("local live=redis.call('EXISTS',unpack(KEYS,1,n))==n"
                        + " and redis.call('EXISTS',unpack(KEYS,n+1,2*n))==0"));
                assertFalse(script.contains("local entities={}"));
                assertFalse(script.contains("local tombstones={}"));
                assertFalse(script.contains("entities[i]=KEYS[i]"));
                assertFalse(script.contains("tombstones[i]=KEYS[n+i]"));
            }
        }
        for (int size : new int[]{0, 513}) {
            var keys = Collections.nCopies(size, "{t4}:key");
            assertThrows(IllegalArgumentException.class, () -> RedisClientOperations.indexAggregateCommand(
                    keys, keys, keys, null, null, null, true));
        }
    }

    @Test void facetLivenessUsesDeclaredKeyRangesWithoutScratchCopies() {
        for (boolean owned : List.of(false, true)) {
            var command = RedisClientOperations.facetIndexCommand(ENTITIES, TOMBSTONES, METADATA,
                    owned ? OWNERS : null, owned ? "tenant-a" : null, "group", null, 1234, true);
            var expected = new ArrayList<String>();
            expected.addAll(ENTITIES); expected.addAll(TOMBSTONES); expected.addAll(METADATA);
            if (owned) expected.addAll(OWNERS);
            assertEquals(expected, command.keys());
            String script = command.script();
            int initial = script.indexOf("local allLive=false");
            int capability = script.indexOf("if aclCheck then", initial);
            int entities = script.indexOf("local entities=pcall('EXISTS',unpack(KEYS,1,n))");
            int entityFailure = script.indexOf("if type(entities)=='table' and entities.err then");
            int entityPermission = script.indexOf("if aclCheck('EXISTS',unpack(KEYS,1,n)) then return entities end");
            int complete = script.indexOf("elseif entities==n then");
            int tombstones = script.indexOf("local tombstones=pcall('EXISTS',unpack(KEYS,n+1,2*n))");
            int tombstoneFailure = script.indexOf("if type(tombstones)=='table' and tombstones.err then");
            int tombstonePermission = script.indexOf("if aclCheck('EXISTS',unpack(KEYS,n+1,2*n)) then return tombstones end");
            int proof = script.indexOf("else allLive=tombstones==0 end");
            int owners = script.indexOf("local ownerValues=false");
            int canonical = script.indexOf("local allowed=allLive or call('EXISTS',KEYS[i])==1"
                    + " and call('EXISTS',KEYS[tombOffset+i])==0");
            assertTrue(initial >= 0 && initial < capability && capability < entities
                    && entities < entityFailure && entityFailure < entityPermission
                    && entityPermission < complete && complete < tombstones
                    && tombstones < tombstoneFailure && tombstoneFailure < tombstonePermission
                    && tombstonePermission < proof && proof < owners && owners < canonical);
            assertEquals(entityPermission, script.lastIndexOf("if aclCheck('EXISTS',unpack(KEYS,1,n)) then return entities end"));
            assertEquals(tombstonePermission, script.lastIndexOf("if aclCheck('EXISTS',unpack(KEYS,n+1,2*n)) then return tombstones end"));
            assertTrue(script.substring(proof, owners).stripTrailing().endsWith("end"));
            assertFalse(script.contains("local entities={}"));
            assertFalse(script.contains("local tombstones={}"));
            assertFalse(script.contains("entities[i]=KEYS[i]"));
            assertFalse(script.contains("tombstones[i]=KEYS[n+i]"));
        }
    }

    @Test void compactFacetCountReadsOnceAndChecksGroupLimitBeforeUpdating() {
        String script = RedisClientOperations.facetIndexCommand(ENTITIES, TOMBSTONES, METADATA,
                null, null, "group", null, 1234, true).script();
        int read = script.indexOf("local current=counts[group]");
        int fresh = script.indexOf("if not current then");
        int increment = script.indexOf("groups=groups+1");
        int limit = script.indexOf("if groups>256 then return redis.error_reply('Facet group limit exceeded: max 256') end");
        int initial = script.indexOf("current=0");
        int update = script.indexOf("counts[group]=current+1");
        assertTrue(read >= 0 && read < fresh && fresh < increment && increment < limit
                && limit < initial && initial < update);
        String counter = script.substring(read, update + "counts[group]=current+1".length());
        assertEquals(2, counter.split("counts\\[group\\]", -1).length - 1);
        assertFalse(counter.contains("counts[group]=0"));
        assertFalse(counter.contains("counts[group]=counts[group]+1"));
    }

    @Test void compactPairsRetainOriginalPositionsAndLiteralSentinels() {
        var client = new Client();
        client.reply = List.of(List.of(0L, 1L), List.of(List.of("__NULL__", "__NULL__"), List.of("", "12.50")));
        var result = client.facetIndexBatch(ENTITIES, TOMBSTONES, METADATA, OWNERS, "a", "group", "amount").orElseThrow();
        assertEquals(new RedisClientOperations.FacetIndexRow(0, "__NULL__", "__NULL__"), result.rows().get(0));
        assertEquals(new RedisClientOperations.FacetIndexRow(1, "", "12.50"), result.rows().get(1));
        assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
        client.reply = List.of(List.of(1L), List.of(Arrays.asList("group", null)));
        assertNull(client.facetIndexBatch(ENTITIES, TOMBSTONES, METADATA, null, null, "group", null)
                .orElseThrow().rows().get(0).valueRaw());
    }

    @Test void malformedRepliesAndNonMonotonicPositionsFailClosed() {
        var client = new Client();
        for (Object reply : List.of(
                List.of(-1L, List.of(), List.of()), List.of(3L, List.of(), List.of()),
                List.of(1L, List.of("1", "2"), List.of()), List.of(0L, List.of(), List.of(2L)),
                List.of(0L, List.of(), List.of(1L, 0L)), List.of(0.5, List.of(), List.of()))) {
            client.reply = reply;
            assertThrows(IllegalStateException.class, () -> client.aggregateIndexBatch(ENTITIES, TOMBSTONES, METADATA, "amount", true));
        }
        for (Object reply : List.of(
                List.of(List.of(0L), List.of()), List.of(List.of(2L), List.of(List.of("g", "1"))),
                List.of(List.of(0L, 0L), List.of(List.of("g", "1"), List.of("h", "2"))),
                List.of(List.of(0L), List.of(Arrays.asList("g", null))),
                List.of(List.of(0.5), List.of(List.of("g", "1"))))) {
            client.reply = reply;
            assertThrows(IllegalStateException.class, () -> client.facetIndexBatch(
                    ENTITIES, TOMBSTONES, METADATA, null, null, "group", "amount"));
        }
    }

    @Test void facetPhaseReusesLegacyScriptAndOnlyAddsTheInternalBudgetArgument() {
        var client = new Client();
        client.reply = List.of(List.of(), List.of());
        assertTrue(client.facetIndexBatch(ENTITIES, TOMBSTONES, METADATA, OWNERS,
                "tenant-a", "group", null).orElseThrow().rows().isEmpty());
        var legacy = RedisClientOperations.facetIndexCommand(ENTITIES, TOMBSTONES, METADATA, OWNERS,
                "tenant-a", "group", null, 0);
        var phase = RedisClientOperations.facetIndexCommand(ENTITIES, TOMBSTONES, METADATA, OWNERS,
                "tenant-a", "group", null, 1234);
        assertEquals(client.script, legacy.script());
        assertEquals(legacy.script(), phase.script());
        assertEquals(client.keys, phase.keys());
        assertEquals(List.of("group", "", "2", "1", "tenant-a"), legacy.arguments());
        assertEquals(legacy.arguments(), phase.arguments().subList(0, 5));
        assertEquals("1234", phase.arguments().get(5));
        assertTrue(phase.script().contains("bytes=bytes+gl+vl"));
        assertTrue(phase.script().indexOf("if bytes>16777216") < phase.script().indexOf("if budget and bytes>budget"));
        assertTrue(phase.script().indexOf("local group=redis.call('HGET'")
                > phase.script().indexOf("exceeded=true; positions={}; pairs={}"));
        for (long invalid : List.of(-1L, 16L * 1024 * 1024 + 1))
            assertThrows(IllegalArgumentException.class, () -> RedisClientOperations.facetIndexCommand(
                    ENTITIES, TOMBSTONES, METADATA, OWNERS, "tenant-a", "group", null, invalid));
        // A legacy single-window caller must never interpret a marker as an empty facet result.
        client.reply = List.of("cachedb-facet-phase-budget-v1", 2L, 1234L);
        assertThrows(IllegalStateException.class, () -> client.facetIndexBatch(
                ENTITIES, TOMBSTONES, METADATA, OWNERS, "tenant-a", "group", null));
    }

    @Test void sharedFacetParserKeepsFieldPositionsLiteralValuesAndExactDecodedByteAdmission() {
        Object response = List.of(List.of(0L, 1L), List.of(List.of("", "__NULL__"), List.of("\u015f", "1")));
        var result = RedisClientOperations.parseFacetIndexResponse(response, 2, "amount", 20).orElseThrow();
        assertEquals(List.of(new RedisClientOperations.FacetIndexRow(0, "", "__NULL__"),
                new RedisClientOperations.FacetIndexRow(1, "\u015f", "1")), result.rows());
        assertThrows(IllegalStateException.class,
                () -> RedisClientOperations.parseFacetIndexResponse(response, 2, "amount", 19));
        assertThrows(IllegalStateException.class,
                () -> RedisClientOperations.parseFacetIndexResponse(response, 1, "amount", 20));
    }

    @Test void livePayloadRepliesPreserveNullPositionsAndAreImmutable() {
        var client = new Client(); client.reply = Arrays.asList("payload", null);
        var values = client.liveIndexPayloadBatch(ENTITIES, TOMBSTONES, OWNERS, "a").orElseThrow();
        assertEquals(Arrays.asList("payload", null), values);
        assertEquals(6, client.keys.size());
        assertThrows(UnsupportedOperationException.class, () -> values.clear());
        client.reply = List.of("x".repeat(8 * 1024 * 1024 + 1), false);
        assertThrows(IllegalStateException.class, () -> client.liveIndexPayloadBatch(ENTITIES, TOMBSTONES, OWNERS, "a"));
    }
}
