package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Protocol.Command;

/** Native preflight/atomicity only; genuine root/SQL authority has a separate provider test. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(30)
class FreshOwnerAdmissionScriptRedisTest {
    @Test void successfulAdmissionCreatesOnlyPersistentOwnerAndZeroVersionPairsAndCannotReplay() throws Exception {
        try (var f = new Fixture()) {
            String counter = f.redis.get(f.keys.get(3));
            assertEquals(1L, f.eval(f.redis));
            for (int row = 0; row < 3; row++) {
                assertEquals("tenant-a", f.redis.get(f.owner(row)));
                assertEquals(-1L, f.redis.pttl(f.owner(row)));
                assertEquals("0", f.redis.get(f.key(row, 1)));
                assertEquals(-1L, f.redis.pttl(f.key(row, 1)));
                for (int kind = 0; kind < 9; kind++)
                    if (kind != 1 && kind != 2) assertFalse(f.redis.exists(f.key(row, kind)));
            }
            assertEquals(counter, f.redis.get(f.keys.get(3)), "Admission must not consume a write version");
            assertThrows(RuntimeException.class, () -> f.eval(f.redis));
            for (int row = 0; row < 3; row++) {
                assertEquals("tenant-a", f.redis.get(f.owner(row)));
                assertEquals("0", f.redis.get(f.key(row, 1)));
            }
            assertEquals(counter, f.redis.get(f.keys.get(3)));
        }
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5,6,7,8})
    void anyExistingIdentityKeyRejectsTheWholeRangeWithoutOverwritingIt(int kind) throws Exception {
        try (var f = new Fixture()) {
            f.redis.hset(f.key(1, kind), "foreign", "unchanged");
            assertThrows(RuntimeException.class, () -> f.eval(f.redis));
            f.noNewMarkers(kind == 2 ? Set.of(1) : Set.of(), kind == 1 ? Set.of(1) : Set.of());
            assertEquals(Map.of("foreign", "unchanged"), f.redis.hgetAll(f.key(1, kind)));
        }
    }

    @Test void changedRunGenerationControlAndRosterEachLeaveNoOwners() throws Exception {
        for (String field : List.of("run_id", "generation", "epoch", "state", "i:active", "i:active_range")) {
            try (var f = new Fixture()) {
                f.redis.hset(f.keys.getFirst(), field, "changed");
                assertThrows(RuntimeException.class, () -> f.eval(f.redis)); f.noNewOwners(Set.of());
            }
        }
        try (var f = new Fixture()) {
            f.redis.hset(f.keys.get(1), "coverage", "changed");
            assertThrows(RuntimeException.class, () -> f.eval(f.redis)); f.noNewOwners(Set.of());
        }
    }

    @Test void malformedOrExpiredVersionAllocatorDoesNotMutateControlOrOwners() throws Exception {
        for (String field : List.of("run_id", "generation", "control_epoch", "floor", "ceiling", "inventory", "request", "reservation_epoch")) {
            try (var f = new Fixture()) {
                f.redis.hset(f.keys.get(2), field, "invalid");
                var before = f.redis.hgetAll(f.keys.getFirst());
                assertThrows(RuntimeException.class, () -> f.eval(f.redis));
                assertEquals(before, f.redis.hgetAll(f.keys.getFirst())); f.noNewOwners(Set.of());
            }
        }
        try (var f = new Fixture()) {
            for (String field : List.of("floor", "ceiling", "inventory")) {
                String original=f.redis.hget(f.keys.get(2),field);
                f.redis.hset(f.keys.get(2),field,field.equals("inventory")?"e".repeat(64):"101");
                f.rejectsWithoutMutation("CDB_FRESH_VERSION_CHANGED");
                f.redis.hset(f.keys.get(2),field,original);
            }
            f.redis.pexpire(f.keys.get(3), 60000);
            assertThrows(RuntimeException.class, () -> f.eval(f.redis)); f.noNewOwners(Set.of());
        }
    }

    @Test void oversizedCounterRejectsWithoutControlEnrollmentOrOwnerMutation() throws Exception {
        try (var f = new Fixture()) {
            f.redis.set(f.keys.get(3), "9".repeat(65536));
            f.rejectsWithoutMutation("CDB_FRESH_VERSION_CHANGED");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "0,run_id,CDB_FRESH_GENERATION_CHANGED",
            "0,state,CDB_FRESH_GENERATION_CHANGED",
            "0,generation,CDB_FRESH_GENERATION_CHANGED",
            "0,epoch,CDB_FRESH_GENERATION_CHANGED",
            "1,run_id,CDB_FRESH_RUNTIME_CHANGED"
    })
    void oversizedControlFieldsRejectWithoutEnrollmentOrOwnerMutation(int key, String field, String error) throws Exception {
        try (var f = new Fixture()) {
            f.redis.hset(f.keys.get(key), field, "9".repeat(65536));
            f.rejectsWithoutMutation(error);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run_id", "generation", "control_epoch", "floor", "ceiling", "inventory"})
    void oversizedVersionFieldsRejectWithoutControlEnrollmentOrOwnerMutation(String field) throws Exception {
        try (var f = new Fixture()) {
            f.redis.hset(f.keys.get(2), field, "9".repeat(65536));
            f.rejectsWithoutMutation("CDB_FRESH_VERSION_CHANGED");
        }
    }

    @Test void msetnxAclDenialCannotLeaveAPartiallyAdmittedRange() throws Exception {
        try (var f = new Fixture()) {
            String user = "fresh_" + UUID.randomUUID().toString().replace("-", "");
            String password = UUID.randomUUID().toString();
            try {
                f.redis.sendCommand(Command.ACL, "SETUSER", user, "reset", "on", ">" + password,
                        "~" + f.prefix + "*", "+@all", "-msetnx");
                try (var denied = new JedisPooled(URI.create("redis://" + user + ":" + password + "@"
                        + f.host + ":" + f.port), 2000)) {
                    var failure = assertThrows(RuntimeException.class, () -> f.eval(denied));
                    assertTrue(failure.getMessage().contains("CDB_FRESH_OWNER_DENIED"));
                }
                f.noNewOwners(Set.of());
            } finally { f.redis.sendCommand(Command.ACL, "DELUSER", user); }
        }
    }

    @Test void existingZeroVersionRejectsTheWholeRangeWithoutClaimingAnyOwner() throws Exception {
        try (var f = new Fixture()) {
            f.redis.set(f.key(1, 1), "0");
            String counter = f.redis.get(f.keys.get(3));
            assertThrows(RuntimeException.class, () -> f.eval(f.redis));
            f.noNewMarkers(Set.of(), Set.of(1));
            assertEquals("0", f.redis.get(f.key(1, 1)));
            assertEquals(-1L, f.redis.pttl(f.key(1, 1)));
            assertEquals(counter, f.redis.get(f.keys.get(3)));
        }
    }

    @ParameterizedTest @ValueSource(ints = {128,129})
    void markerPairLimitIsBoundedAndDoesNotConsumeVersions(int count) throws Exception {
        try (var f = new Fixture()) {
            for (int row = 3; row < count; row++)
                for (int kind = 0; kind < 9; kind++) f.keys.add(f.prefix + row + ":" + kind);
            f.args.set(4, Integer.toString(count));
            String counter = f.redis.get(f.keys.get(3));
            if (count > 128) {
                assertThrows(RuntimeException.class, () -> f.eval(f.redis));
                for (int i = 4; i < f.keys.size(); i++) assertFalse(f.redis.exists(f.keys.get(i)));
            } else {
                assertEquals(1L, f.eval(f.redis));
                for (int row = 0; row < count; row++) {
                    assertEquals("tenant-a", f.redis.get(f.owner(row)));
                    assertEquals("0", f.redis.get(f.key(row, 1)));
                    assertEquals(-1L, f.redis.pttl(f.owner(row)));
                    assertEquals(-1L, f.redis.pttl(f.key(row, 1)));
                    for (int kind = 0; kind < 9; kind++)
                        if (kind != 1 && kind != 2) assertFalse(f.redis.exists(f.key(row, kind)));
                }
            }
            assertEquals(counter, f.redis.get(f.keys.get(3)));
        }
    }

    @ParameterizedTest @ValueSource(ints = {1,2})
    void denyingOneVersionOrOwnerWriteCannotLeaveEitherMarker(int deniedKind) throws Exception {
        try (var f = new Fixture()) {
            String user = "fresh_" + UUID.randomUUID().toString().replace("-", "");
            String password = UUID.randomUUID().toString();
            try {
                var rules = new ArrayList<>(List.of("SETUSER", user, "reset", "on", ">" + password,
                        "~" + f.prefix + "*", "+@all", "-msetnx"));
                var allowedWrites = new StringJoiner(" ", "(+msetnx ", ")");
                var markers = new ArrayList<String>();
                for (int row = 0; row < 3; row++) {
                    markers.add(f.owner(row)); markers.add(f.args.get(3));
                    markers.add(f.key(row, 1)); markers.add("0");
                    for (int kind : List.of(1, 2)) {
                        String key = f.key(row, kind);
                        if (!key.equals(f.key(1, deniedKind))) allowedWrites.add("%W~" + key);
                    }
                }
                rules.add(allowedWrites.toString());
                f.redis.sendCommand(Command.ACL, rules.toArray(String[]::new));
                String counter = f.redis.get(f.keys.get(3));
                try (var denied = new JedisPooled(URI.create("redis://" + user + ":" + password + "@"
                        + f.host + ":" + f.port), 2000)) {
                    assertEquals(1L, denied.eval("return 1", f.keys, List.of()),
                            "The fixture must admit EVAL with the actual script keys");
                    assertEquals(0L, denied.eval("return redis.acl_check_cmd('MSETNX', unpack(ARGV)) and 1 or 0",
                            f.keys, markers), "The exact owner/version MSETNX vector must be denied");
                    var failure = assertThrows(RuntimeException.class, () -> f.eval(denied));
                    assertTrue(failure.getMessage().contains("CDB_FRESH_OWNER_DENIED"));
                }
                f.noNewOwners(Set.of());
                assertEquals(counter, f.redis.get(f.keys.get(3)));
            } finally { f.redis.sendCommand(Command.ACL, "DELUSER", user); }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String host = System.getProperty("cachedb.snapshot.redis.host", "redis");
        final int port = Integer.getInteger("cachedb.snapshot.redis.port", 6379);
        final String prefix = "fresh_native_" + UUID.randomUUID() + ":";
        final JedisPooled redis = new JedisPooled(host, port);
        final List<String> keys = new ArrayList<>(), args = new ArrayList<>();
        final String script;
        Fixture() throws Exception {
            try (var input = getClass().getResourceAsStream("/functions/cachedb-fresh-owner-admission.lua")) {
                assertNotNull(input); script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            Object raw = redis.sendCommand(Command.INFO, "server");
            String info = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : raw.toString();
            String run = Arrays.stream(info.split("\r\n"))
                    .filter(line -> line.startsWith("run_id:")).findFirst().orElseThrow().substring(7);
            keys.addAll(List.of(prefix + "generation", prefix + "roster", prefix + "allocator", prefix + "counter"));
            redis.hset(keys.getFirst(), Map.of("run_id", run, "state", "OPEN", "generation", "2", "epoch", "7"));
            var grant=new TreeMap<>(Map.of("epoch","7","operation","11111111-1111-1111-1111-111111111111",
                    "i:active","a".repeat(64),"i:active_range","b".repeat(64)));
            redis.hset(keys.getFirst(),grant);
            var roster = new TreeMap<>(Map.of("schema", "2", "state", "SEALED", "approval", "a".repeat(64),
                    "manifest", "b".repeat(64), "coverage", "c".repeat(64)));
            var stored = new HashMap<>(roster); stored.put("run_id", run); redis.hset(keys.get(1), stored);
            redis.hset(keys.get(2), Map.of("run_id", run, "generation", "2", "control_epoch", "7",
                    "inventory", "d".repeat(64), "floor", "100", "ceiling", "200"));
            redis.set(keys.get(3), "100");
            String request="22222222-2222-2222-2222-222222222222";
            redis.hset(keys.get(2),Map.of("request",request,"reservation_epoch","3"));
            for (int row = 0; row < 3; row++)
                for (int kind = 0; kind < 9; kind++) keys.add(prefix + row + ":" + kind);
            args.addAll(List.of(run, "2", "7", "tenant-a", "3", Integer.toString(roster.size())));
            roster.forEach((field, value) -> { args.add(field); args.add(value); });
            args.addAll(List.of("100","200","d".repeat(64),request,"3",Integer.toString(grant.size())));
            grant.forEach((field,value)->{args.add(field);args.add(value);});
        }
        String key(int row, int kind) { return keys.get(4 + row * 9 + kind); }
        String owner(int row) { return key(row, 2); }
        Object eval(JedisPooled client) { return client.eval(script, keys, args); }
        void rejectsWithoutMutation(String error) {
            var before = List.of(redis.hgetAll(keys.get(0)), redis.hgetAll(keys.get(1)), redis.hgetAll(keys.get(2)));
            String counter = redis.get(keys.get(3));
            var failure = assertThrows(RuntimeException.class, () -> eval(redis));
            assertTrue(failure.getMessage().contains(error));
            for (int key = 0; key < before.size(); key++) assertEquals(before.get(key), redis.hgetAll(keys.get(key)));
            assertEquals(counter, redis.get(keys.get(3)));
            for (int row = 0; row < 3; row++)
                for (int kind = 0; kind < 9; kind++) assertFalse(redis.exists(key(row, kind)));
        }
        void noNewOwners(Set<Integer> preserved) {
            noNewMarkers(preserved, Set.of());
        }
        void noNewMarkers(Set<Integer> preservedOwners, Set<Integer> preservedVersions) {
            for (int row = 0; row < 3; row++) {
                if (!preservedOwners.contains(row)) assertFalse(redis.exists(owner(row)));
                if (!preservedVersions.contains(row)) assertFalse(redis.exists(key(row, 1)));
            }
        }
        @Override public void close() {
            try { redis.del(keys.toArray(String[]::new)); } finally { redis.close(); }
        }
    }
}
