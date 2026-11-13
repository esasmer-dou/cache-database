package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;

@Timeout(120)
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisFacetLuaOwnerSemanticsRedisTest {
    private static final int ROWS = 9;
    private static final String TENANT = "\u015firket";
    private static final String FOREIGN = "\u015firkez";
    private static final String GROUP = "group";
    private static final String VALUE = "value";
    private static final List<Integer> ALL = IntStream.range(0, ROWS).boxed().toList();
    private static final List<Integer> EDGES = List.of(0, ROWS / 2, ROWS - 1);
    private static final List<Mode> MODES = List.of(
            new Mode(true, null), new Mode(false, null), new Mode(false, VALUE));

    @Test void allEqualOwnersPreserveEveryCandidateAndNullPosition() {
        try (var f = new Fixture()) {
            f.assertRows(f.admin, ALL, true);
        }
    }

    @Test void sameLengthForeignOwnerAtFirstMiddleLastNeverAuthorizesMetadata() {
        try (var f = new Fixture()) {
            for (int row : EDGES) {
                f.admin.set(f.owners.get(row), FOREIGN);
                f.poisonMetadata(row);
                f.assertRows(f.admin, except(row), true);
                f.restore(row);
            }
        }
    }

    @Test void missingEmptyShortAndLongOwnersKeepSparseMgetPositionsAligned() {
        try (var f = new Fixture()) {
            for (int row : EDGES) {
                for (String owner : Arrays.asList(null, "", "s", TENANT + "x")) {
                    f.admin.del(f.owners.get(row));
                    if (owner != null) f.admin.set(f.owners.get(row), owner);
                    f.poisonMetadata(row);
                    f.assertRows(f.admin, except(row), true);
                    f.restore(row);
                }
            }
        }
    }

    @Test void mixedOwnerFilteringRetainsOriginalSentinelAndGroupPositions() {
        try (var f = new Fixture()) {
            f.admin.del(f.owners.get(0));
            f.admin.set(f.owners.get(2), "s");
            f.admin.set(f.owners.get(3), FOREIGN);
            f.admin.set(f.owners.get(5), TENANT + "x");
            for (int row : List.of(0, 2, 3, 5)) f.poisonMetadata(row);
            f.assertRows(f.admin, List.of(1, 4, 6, 7, 8), true);
        }
    }

    @Test void allowedMgetDoesNotRequireGetCommandPermission() {
        try (var f = new Fixture(); var scoped = f.restricted(OwnerReads.MGET, Set.of(), true)) {
            assertThrowsExactly(JedisAccessControlException.class, () -> scoped.client.get(f.owners.get(0)));
            assertEquals(java.util.Collections.nCopies(ROWS, TENANT),
                    scoped.client.mget(f.owners.toArray(String[]::new)));
            f.assertRows(scoped.client, ALL, true);
            scoped.assertOutsideDenied();
        }
    }

    @Test void commandDeniedMgetUsesPermittedGetWithoutWideningPermissions() {
        try (var f = new Fixture(); var scoped = f.restricted(OwnerReads.GET, Set.of(), true)) {
            assertThrowsExactly(JedisAccessControlException.class,
                    () -> scoped.client.mget(f.owners.toArray(String[]::new)));
            assertEquals(TENANT, scoped.client.get(f.owners.get(0)));
            f.assertRows(scoped.client, ALL, true);
            f.admin.del(f.owners.get(0));
            f.admin.set(f.owners.get(2), "s");
            f.admin.set(f.owners.get(3), FOREIGN);
            f.admin.set(f.owners.get(5), TENANT + "x");
            for (int row : List.of(0, 2, 3, 5)) f.poisonMetadata(row);
            f.assertRows(scoped.client, List.of(1, 4, 6, 7, 8), true);
            scoped.assertOutsideDenied();
        }
    }

    @Test void noLengthQualifiedOwnersReadNeitherOwnerValuesNorForeignMetadata() {
        try (var f = new Fixture(); var scoped = f.restricted(OwnerReads.NONE, Set.of(), false)) {
            for (int row : ALL) {
                f.admin.del(f.owners.get(row));
                if (row % 3 != 0) f.admin.set(f.owners.get(row), row % 3 == 1 ? "" : TENANT + "x");
                f.poisonMetadata(row);
            }
            assertThrowsExactly(JedisAccessControlException.class, () -> scoped.client.get(f.owners.get(0)));
            assertThrowsExactly(JedisAccessControlException.class,
                    () -> scoped.client.mget(f.owners.toArray(String[]::new)));
            assertThrowsExactly(JedisAccessControlException.class,
                    () -> scoped.client.hget(f.metadata.get(0), GROUP));
            f.assertRows(scoped.client, List.of(), true);
            scoped.assertOutsideDenied();
        }
    }

    @Test void ownerWrongtypeAndAdmittedMetadataPoisonAtFirstMiddleLastRemainErrors() {
        try (var f = new Fixture()) {
            for (int row : EDGES) {
                f.admin.del(f.owners.get(row));
                f.admin.hset(f.owners.get(row), "not", "a string");
                f.assertFailure(f.admin, "WRONGTYPE", 0, true);
                f.restore(row);
                f.poisonMetadata(row);
                f.assertFailure(f.admin, "WRONGTYPE", 0, true);
                f.restore(row);
                f.admin.hdel(f.metadata.get(row), GROUP);
                f.assertFailure(f.admin, "Incomplete facet index metadata", 0, true);
                f.restore(row);
                f.admin.hdel(f.metadata.get(row), VALUE);
                var failure = assertThrowsExactly(JedisDataException.class,
                        () -> f.eval(f.admin, new Mode(false, VALUE), 0, true));
                assertTrue(failure.getMessage().contains("Incomplete facet index metadata"));
                f.restore(row);
            }
            f.assertRows(f.admin, ALL, true);
        }
    }

    @Test void deadRowsDoNotReadWrongtypeOwnersOrMetadata() {
        try (var f = new Fixture()) {
            f.admin.del(f.entities.get(0));
            f.admin.set(f.tombstones.get(ROWS / 2), "deleted");
            f.admin.set(f.tombstones.get(ROWS - 1), "deleted");
            for (int row : EDGES) {
                f.admin.del(f.owners.get(row));
                f.admin.hset(f.owners.get(row), "not", "a string");
                f.poisonMetadata(row);
            }
            f.assertRows(f.admin, except(0, ROWS / 2, ROWS - 1), true);
        }
    }

    @Test void phaseBudgetMarkerDoesNotHideLaterWrongtypeOrMissingMetadata() {
        try (var f = new Fixture()) {
            for (Mode mode : MODES) {
                assertEquals(List.of("cachedb-facet-phase-budget-v1", (long) ROWS, 1L),
                        f.eval(f.admin, mode, 1, true));
            }
            for (int row : List.of(ROWS / 2, ROWS - 1)) {
                f.poisonMetadata(row);
                f.assertFailure(f.admin, "WRONGTYPE", 1, true);
                f.restore(row);
                f.admin.hdel(f.metadata.get(row), GROUP);
                f.assertFailure(f.admin, "Incomplete facet index metadata", 1, true);
                f.restore(row);
                f.admin.hdel(f.metadata.get(row), VALUE);
                var missingValue = assertThrowsExactly(JedisDataException.class,
                        () -> f.eval(f.admin, new Mode(false, VALUE), 1, true));
                assertTrue(missingValue.getMessage().contains("Incomplete facet index metadata"));
                f.restore(row);
            }
        }
    }

    @Test void phaseBudgetStillExecutesLaterHgetAndPreservesItsAclFailure() {
        try (var f = new Fixture(); var scoped = f.restricted(OwnerReads.BOTH, Set.of(ROWS - 1), true)) {
            String last = f.metadata.get(ROWS - 1);
            assertThrowsExactly(JedisAccessControlException.class, () -> scoped.client.hget(last, GROUP));
            var expected = assertThrowsExactly(JedisDataException.class, () -> scoped.client.eval(
                    "return redis.call('HGET',KEYS[1],ARGV[1])", List.of(last), List.of(GROUP)));
            String prefix = luaErrorPrefix(expected);
            for (Mode mode : MODES) {
                var actual = assertThrowsExactly(JedisDataException.class,
                        () -> f.eval(scoped.client, mode, 1, true));
                assertTrue(actual.getMessage().startsWith(prefix + " script: "));
            }
            assertHealthy(scoped.client);
            scoped.assertOutsideDenied();
        }
    }

    @Test void unownedSpecializationIgnoresOwnerPoisonButRetainsLivenessAndMetadataChecks() {
        try (var f = new Fixture()) {
            for (int row : ALL) {
                f.admin.del(f.owners.get(row));
                f.admin.hset(f.owners.get(row), "not", "a string");
            }
            f.admin.del(f.entities.get(0));
            f.admin.set(f.tombstones.get(ROWS - 1), "deleted");
            f.poisonMetadata(0);
            f.poisonMetadata(ROWS - 1);
            f.assertRows(f.admin, except(0, ROWS - 1), false);
            f.admin.hdel(f.metadata.get(ROWS / 2), GROUP);
            f.assertFailure(f.admin, "Incomplete facet index metadata", 0, false);
        }
    }

    @Test void earlyMetadataErrorPrecedesLaterEntityOrTombstoneExistsDenial() {
        try (var f = new Fixture()) {
            for (String denied : List.of(f.entities.get(ROWS - 1), f.tombstones.get(ROWS - 1))) {
                try (var scoped = f.restricted(OwnerReads.BOTH, Set.of(), true, Set.of(denied), false)) {
                    assertLaterExistsDenied(scoped.client, denied);
                    for (boolean owned : List.of(false, true)) {
                        f.poisonMetadata(0);
                        f.assertFailure(scoped.client, "WRONGTYPE", 0, owned);
                        f.restore(0);
                        f.admin.hdel(f.metadata.get(0), GROUP);
                        f.assertFailure(scoped.client, "Incomplete facet index metadata", 0, owned);
                        f.restore(0);
                    }
                    scoped.assertOutsideDenied();
                }
            }
        }
    }

    @Test void hardByteBudgetPrecedesLaterEntityOrTombstoneExistsDenial() {
        try (var f = new Fixture()) {
            f.admin.hset(f.metadata.get(0), GROUP, "x".repeat(16 * 1024 * 1024 + 1));
            for (String denied : List.of(f.entities.get(ROWS - 1), f.tombstones.get(ROWS - 1))) {
                try (var scoped = f.restricted(OwnerReads.BOTH, Set.of(), true, Set.of(denied), false)) {
                    assertLaterExistsDenied(scoped.client, denied);
                    for (boolean owned : List.of(false, true))
                        f.assertFailure(scoped.client, "Facet byte budget exceeded", 0, owned);
                    scoped.assertOutsideDenied();
                }
            }
        }
    }

    @Test void validPrefixAndSoftBudgetDoNotHideLaterExistsDenial() {
        try (var f = new Fixture()) {
            for (String denied : List.of(f.entities.get(ROWS - 1), f.tombstones.get(ROWS - 1))) {
                try (var scoped = f.restricted(OwnerReads.BOTH, Set.of(), true, Set.of(denied), false)) {
                    String prefix = assertLaterExistsDenied(scoped.client, denied);
                    for (boolean owned : List.of(false, true)) {
                        for (Mode mode : MODES) {
                            for (long budget : List.of(0L, 1L)) {
                                var failure = assertThrowsExactly(JedisDataException.class,
                                        () -> f.eval(scoped.client, mode, budget, owned));
                                assertTrue(failure.getMessage().startsWith(prefix + " script: "), failure.getMessage());
                            }
                        }
                    }
                    scoped.assertOutsideDenied();
                }
            }
        }
    }

    @Test void deadEntityDoesNotRequireItsDeniedTombstoneRead() {
        try (var f = new Fixture(); var scoped = f.restricted(OwnerReads.BOTH, Set.of(), true,
                Set.of(f.tombstones.get(0)), false)) {
            f.admin.del(f.entities.get(0), f.owners.get(0));
            f.admin.hset(f.owners.get(0), "not", "a string");
            f.poisonMetadata(0);
            for (boolean owned : List.of(false, true)) f.assertRows(scoped.client, except(0), owned);
            f.restore(0);
            f.admin.set(f.entities.get(0), "payload");
            String prefix = assertLaterExistsDenied(scoped.client, f.tombstones.get(0));
            for (boolean owned : List.of(false, true)) {
                for (Mode mode : MODES) {
                    var failure = assertThrowsExactly(JedisDataException.class,
                            () -> f.eval(scoped.client, mode, 0, owned));
                    assertTrue(failure.getMessage().startsWith(prefix + " script: "));
                }
            }
            scoped.assertOutsideDenied();
        }
    }

    @Test void separateExistsSelectorsUseCanonicalReadsWithoutWideningPermissions() {
        try (var f = new Fixture(); var scoped = f.restricted(OwnerReads.BOTH, Set.of(), true, Set.of(), true)) {
            assertNull(scoped.client.eval("return redis.acl_check_cmd('EXISTS',unpack(KEYS))",
                    f.entities, List.of()));
            assertNull(scoped.client.eval("return redis.acl_check_cmd('EXISTS',unpack(KEYS))",
                    f.tombstones, List.of()));
            for (String key : f.entities) assertTrue(scoped.client.exists(key));
            for (String key : f.tombstones) assertFalse(scoped.client.exists(key));
            for (boolean owned : List.of(false, true)) f.assertRows(scoped.client, ALL, owned);
            scoped.assertOutsideDenied();
        }
    }

    @Test void unavailableAclCheckAliasSelectsCanonicalOrderWithoutSpeculativeExists() {
        try (var f = new Fixture(); var scoped = f.restricted(OwnerReads.BOTH, Set.of(), true,
                Set.of(f.entities.get(ROWS - 1)), false)) {
            f.poisonMetadata(0);
            for (boolean owned : List.of(false, true)) {
                for (Mode mode : MODES) {
                    var command = RedisClientOperations.facetIndexCommand(f.entities, f.tombstones, f.metadata,
                            owned ? f.owners : null, owned ? TENANT : null, GROUP, mode.valueField(),
                            mode.compact() ? 1024 * 1024 : 0, mode.compact());
                    String script = command.script();
                    assertTrue(script.contains("local aclCheck=redis.acl_check_cmd;"));
                    // Test-local capability absence; this is not a Redis 6 integration claim.
                    script = script.replace("local aclCheck=redis.acl_check_cmd;", "local aclCheck=nil;");
                    final String withoutAclCheck = script;
                    var failure = assertThrowsExactly(JedisDataException.class,
                            () -> scoped.client.eval(withoutAclCheck, command.keys(), command.arguments()));
                    assertTrue(failure.getMessage().startsWith("WRONGTYPE"), failure.getMessage());
                }
            }
            scoped.assertOutsideDenied();
        }
    }

    private static String assertLaterExistsDenied(JedisPooled client, String key) {
        assertThrowsExactly(JedisAccessControlException.class, () -> client.exists(key));
        var failure = assertThrowsExactly(JedisDataException.class, () -> client.eval(
                "return redis.call('EXISTS',KEYS[1])", List.of(key), List.of()));
        return luaErrorPrefix(failure);
    }

    @Test void emptyAndBlankTenantsAreRejectedBeforeConstructingLua() {
        for (String tenant : List.of("", " ", "\t")) {
            assertThrowsExactly(IllegalArgumentException.class,
                    () -> RedisClientOperations.facetIndexCommand(List.of("e"), List.of("t"), List.of("m"),
                            List.of("o"), tenant, GROUP, null, 1024, true));
        }
    }

    private static List<Integer> except(int... removed) {
        return ALL.stream().filter(row -> IntStream.of(removed).noneMatch(value -> value == row)).toList();
    }

    private static String luaErrorPrefix(JedisDataException failure) {
        int context = failure.getMessage().indexOf(" script: ");
        assertTrue(context > 0, "Expected an inner Lua error, not a top-level EVAL rejection");
        return failure.getMessage().substring(0, context);
    }

    private static void assertHealthy(JedisPooled client) {
        assertEquals(0, client.getPool().getNumActive());
        assertEquals("PONG", client.ping());
        assertEquals(0, client.getPool().getNumActive());
    }

    private record Mode(boolean compact, String valueField) {}
    private enum OwnerReads { BOTH, MGET, GET, NONE }

    private static final class Fixture implements AutoCloseable {
        final JedisPooled admin = new JedisPooled(URI.create(RedisTestEndpoint.uri()), 2000);
        final String prefix = "facet-lua-owner-" + UUID.randomUUID() + ":";
        final List<String> entities = keys("entity");
        final List<String> tombstones = keys("tombstone");
        final List<String> metadata = keys("metadata");
        final List<String> owners = keys("owner");
        final List<String> cleanup = new ArrayList<>(4 * ROWS);

        Fixture() {
            cleanup.addAll(entities);
            cleanup.addAll(tombstones);
            cleanup.addAll(metadata);
            cleanup.addAll(owners);
            try {
                try (var pipeline = admin.pipelined()) {
                    for (int row : ALL) {
                        pipeline.set(entities.get(row), "payload");
                        pipeline.set(owners.get(row), TENANT);
                        pipeline.hset(metadata.get(row), Map.of(GROUP, group(row), VALUE, "v" + row));
                    }
                    pipeline.sync();
                }
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
        }

        private List<String> keys(String family) {
            return ALL.stream().map(row -> prefix + family + ":" + row).toList();
        }

        private static String group(int row) { return row % 3 == 1 ? "__NULL__" : "g" + row; }

        void restore(int row) {
            admin.del(owners.get(row), metadata.get(row));
            admin.set(owners.get(row), TENANT);
            admin.hset(metadata.get(row), Map.of(GROUP, group(row), VALUE, "v" + row));
        }

        void poisonMetadata(int row) {
            admin.del(metadata.get(row));
            admin.set(metadata.get(row), "not a hash");
        }

        Object eval(JedisPooled client, Mode mode, long budget, boolean owned) {
            var command = RedisClientOperations.facetIndexCommand(entities, tombstones, metadata,
                    owned ? owners : null, owned ? TENANT : null, GROUP, mode.valueField(),
                    mode.compact() && budget == 0 ? 1024 * 1024 : budget, mode.compact());
            assertEquals((owned ? 4 : 3) * ROWS, command.keys().size());
            return client.eval(command.script(), command.keys(), command.arguments());
        }

        void assertRows(JedisPooled client, List<Integer> expected, boolean owned) {
            for (Mode mode : MODES) {
                Object raw = eval(client, mode, 0, owned);
                if (mode.compact()) {
                    var actual = RedisClientOperations.parseFacetIndexCounts(raw, ROWS, 1024 * 1024);
                    var counts = new LinkedHashMap<String, Long>();
                    var nulls = new ArrayList<Integer>();
                    for (int row : expected) {
                        if (group(row).equals("__NULL__")) nulls.add(row);
                        else counts.merge(group(row), 1L, Long::sum);
                    }
                    assertEquals(counts, actual.counts());
                    assertEquals(nulls, actual.nullCandidates());
                } else {
                    var actual = RedisClientOperations.parseFacetIndexResponse(
                            raw, ROWS, mode.valueField(), 1024 * 1024).orElseThrow();
                    var rows = expected.stream().map(row -> new RedisClientOperations.FacetIndexRow(
                            row, group(row), mode.valueField() == null ? null : "v" + row)).toList();
                    assertEquals(rows, actual.rows());
                }
            }
            assertHealthy(client);
        }

        void assertFailure(JedisPooled client, String fragment, long budget, boolean owned) {
            for (Mode mode : MODES) {
                var failure = assertThrowsExactly(JedisDataException.class, () -> eval(client, mode, budget, owned));
                assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
            }
            assertHealthy(client);
        }

        Scoped restricted(OwnerReads reads, Set<Integer> deniedValues, boolean metadataReadable) {
            return restricted(reads, deniedValues, metadataReadable, Set.of(), false);
        }

        Scoped restricted(OwnerReads reads, Set<Integer> deniedValues, boolean metadataReadable,
                Set<String> deniedLiveness, boolean separateExistsSelectors) {
            return new Scoped(reads, deniedValues, metadataReadable, deniedLiveness, separateExistsSelectors);
        }

        private final class Scoped implements AutoCloseable {
            final String user = "facet_lua_owner_" + UUID.randomUUID().toString().replace("-", "");
            JedisPooled client;
            boolean userCreated;

            Scoped(OwnerReads reads, Set<Integer> deniedValues, boolean metadataReadable,
                    Set<String> deniedLiveness, boolean separateExistsSelectors) {
                try {
                    String password = UUID.randomUUID().toString();
                    admin.sendCommand(Command.ACL, "SETUSER", user, "reset", "on", ">" + password, "resetchannels");
                    userCreated = true;
                    var rules = new ArrayList<>(List.of("SETUSER", user, "clearselectors", "resetkeys", "-@all",
                            "+ping", "+hello", "+client|setinfo"));
                    rules.add(selector("+eval", cleanup));
                    var liveKeys = new ArrayList<>(entities);
                    liveKeys.addAll(tombstones);
                    liveKeys.removeIf(deniedLiveness::contains);
                    if (separateExistsSelectors) {
                        for (String key : liveKeys) rules.add(selector("+exists", List.of(key)));
                    } else {
                        rules.add(selector("+exists", liveKeys));
                    }
                    rules.add(selector("+strlen", owners));
                    if (reads == OwnerReads.BOTH || reads == OwnerReads.MGET) rules.add(selector("+mget", owners));
                    if (reads == OwnerReads.BOTH || reads == OwnerReads.GET) rules.add(selector("+get", owners));
                    if (metadataReadable) {
                        rules.add(selector("+hstrlen +hexists", metadata));
                        var allowed = ALL.stream().filter(row -> !deniedValues.contains(row)).map(metadata::get).toList();
                        if (!allowed.isEmpty()) rules.add(selector("+hget", allowed));
                    }
                    admin.sendCommand(Command.ACL, rules.toArray(String[]::new));
                    var pool = new GenericObjectPoolConfig<Connection>();
                    pool.setMaxTotal(1);
                    pool.setMaxIdle(1);
                    pool.setMaxWait(Duration.ofSeconds(2));
                    pool.setJmxEnabled(false);
                    var endpoint = URI.create(RedisTestEndpoint.uri());
                    client = new JedisPooled(new HostAndPort(endpoint.getHost(), endpoint.getPort()),
                            DefaultJedisClientConfig.builder().user(user).password(password)
                                    .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build(), pool);
                } catch (RuntimeException | Error failure) {
                    try { close(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                    throw failure;
                }
            }

            void assertOutsideDenied() {
                String outside = prefix + "outside";
                assertThrowsExactly(JedisAccessControlException.class, () -> client.get(outside));
                assertThrowsExactly(JedisAccessControlException.class,
                        () -> client.eval("return redis.call('EXISTS',KEYS[1])", List.of(outside), List.of()));
                assertThrowsExactly(JedisAccessControlException.class, () -> client.set(entities.get(0), "forbidden"));
                assertHealthy(client);
            }

            @Override public void close() {
                try { if (client != null) client.close(); }
                finally { if (userCreated) admin.sendCommand(Command.ACL, "DELUSER", user); }
            }
        }

        private static String selector(String commands, List<String> keys) {
            return "(" + commands + " " + String.join(" ", keys.stream().map(key -> "~" + key).toList()) + ")";
        }

        @Override public void close() {
            try { admin.del(cleanup.toArray(String[]::new)); }
            finally { admin.close(); }
        }
    }
}
