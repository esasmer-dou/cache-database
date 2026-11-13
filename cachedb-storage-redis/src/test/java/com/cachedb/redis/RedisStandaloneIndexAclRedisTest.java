package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisStandaloneIndexAclRedisTest {
    @Test void nativeCountTombstoneSelectorsMatchLuaForMissingMixedAndForbiddenLiveRows() {
        for (boolean owned : List.of(false, true)) for (boolean multiDenied : List.of(false, true)) {
            try (Fixture f = new Fixture(multiDenied ? new String[]{"-multi"} : new String[0])) {
                String tenant = owned ? "a" : null;
                var keys = f.binaryCountKeys(1024, tenant);
                if (owned) {
                    String deadOwner = keys.window(0).owners().get(1);
                    f.admin.del(deadOwner); f.admin.hset(deadOwner, Map.of("invalid", "dead-owner"));
                }
                var strategy = new RedisKeyStrategy(f.prefix);
                f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-exists",
                        "(+exists ~" + strategy.entityKey("native-acl", "") + "*)");
                assertCountRoutes(f, keys, tenant, 0);

                var rules = new ArrayList<String>(List.of("SETUSER", f.user));
                for (int row : List.of(0, 511, 512, 1023)) {
                    var window = keys.window(row / 512 * 512);
                    f.admin.set(window.entities().get(row % 512), "payload");
                    rules.add("(+exists ~" + window.tombstones().get(row % 512) + ")");
                }
                f.admin.sendCommand(Command.ACL, rules.toArray(String[]::new));
                assertCountRoutes(f, keys, tenant, 4);
                f.admin.set(keys.window(0).tombstones().getFirst(), "deleted");
                assertCountRoutes(f, keys, tenant, 3);

                // This live row has no tombstone permission. Lua must reject, not return a partial count.
                f.admin.set(keys.window(0).entities().get(1), "payload");
                var direct = assertThrows(JedisDataException.class, () ->
                        RedisStandaloneIndexSnapshot.indexCountEvals(f.client.getPool(), 1024, keys::window, tenant));
                var generic = assertThrows(JedisDataException.class, () ->
                        f.operations.aggregateIndexCounts(1024, keys::window, tenant));
                var binary = assertThrows(JedisDataException.class, () -> f.operations.binaryIndexCounts(keys, tenant));
                assertEquals(direct.getClass(), generic.getClass());
                assertEquals(direct.getClass(), binary.getClass());
                assertEquals(direct.getMessage(), generic.getMessage());
                assertEquals(direct.getMessage(), binary.getMessage());
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());

            }
        }
    }

    @Test void earlierTombstoneAclAndLaterLiveOwnerWrongtypeKeepDirectLuaWindowOrder() {
        for (boolean multiDenied : List.of(false, true)) for (boolean earlierLuaFails : List.of(false, true)) {
            try (Fixture f = new Fixture(multiDenied ? new String[]{"-multi"} : new String[0])) {
                var keys = f.binaryCountKeys(1024, "a");
                var first = keys.window(0);
                var second = keys.window(512);
                try (var pipeline = f.admin.pipelined()) {
                    if (earlierLuaFails) for (String entity : first.entities()) pipeline.set(entity, "payload");
                    for (String entity : second.entities()) pipeline.set(entity, "payload");
                    pipeline.sync();
                }
                String owner = second.owners().getFirst();
                f.admin.del(owner); f.admin.hset(owner, Map.of("invalid", "live-owner"));
                String permittedTombs = String.join(" ", second.tombstones().stream().map(key -> "~" + key).toList());
                f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-exists",
                        "(+exists ~" + new RedisKeyStrategy(f.prefix).entityKey("native-acl", "")
                                + "* " + permittedTombs + ")");

                var direct = assertThrows(JedisDataException.class, () ->
                        RedisStandaloneIndexSnapshot.indexCountEvals(f.client.getPool(), 1024, keys::window, "a"));
                assertEquals(!earlierLuaFails, direct.getMessage().startsWith("WRONGTYPE"));
                var generic = assertThrows(JedisDataException.class, () ->
                        f.operations.aggregateIndexCounts(1024, keys::window, "a"));
                var binary = assertThrows(JedisDataException.class, () -> f.operations.binaryIndexCounts(keys, "a"));
                assertEquals(direct.getClass(), generic.getClass());
                assertEquals(direct.getClass(), binary.getClass());
                assertEquals(direct.getMessage(), generic.getMessage());
                assertEquals(direct.getMessage(), binary.getMessage());
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());

                f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-eval");
                assertTrue(RedisStandaloneIndexSnapshot.indexCountEvals(
                        f.client.getPool(), 1024, keys::window, "a").isEmpty());
                var deniedGeneric = assertThrows(JedisDataException.class, () ->
                        f.operations.aggregateIndexCounts(1024, keys::window, "a"));
                var deniedBinary = assertThrows(JedisDataException.class, () ->
                        f.operations.binaryIndexCounts(keys, "a"));
                assertTrue(deniedGeneric.getMessage().startsWith("WRONGTYPE"));
                assertEquals(deniedGeneric.getClass(), deniedBinary.getClass());
                assertEquals(deniedGeneric.getMessage(), deniedBinary.getMessage());
                assertEquals(0, f.client.getPool().getNumActive());
            }
        }
    }

    @Test void sameWindowOwnerKeyDenialAndWrongtypeKeepDirectLuaRowOrder() {
        for (boolean multiDenied : List.of(false, true)) for (boolean legacyReadDenied : List.of(false, true)) {
            var denied = new ArrayList<String>(List.of("-getrange"));
            if (multiDenied) denied.add("-multi");
            if (legacyReadDenied) denied.add("-strlen");
            try (Fixture f = new Fixture(denied.toArray(String[]::new))) {
                var keys = f.binaryCountKeys(1024, "a");
                try (var pipeline = f.admin.pipelined()) {
                    for (int start = 0; start < 1024; start += 512)
                        for (String entity : keys.window(start).entities()) pipeline.set(entity, "payload");
                    pipeline.sync();
                }
                String firstOwner = keys.window(0).owners().getFirst();
                String wrongtypeOwner = keys.window(0).owners().get(1);
                f.admin.del(wrongtypeOwner);
                f.admin.hset(wrongtypeOwner, Map.of("invalid", "live-owner"));
                f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "(+getrange ~" + wrongtypeOwner + ")");
                assertThrowsExactly(JedisAccessControlException.class, () -> f.client.getrange(firstOwner, 0, 1));
                var nativeWrongtype = assertThrowsExactly(JedisDataException.class,
                        () -> f.client.getrange(wrongtypeOwner, 0, 1));
                assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value",
                        nativeWrongtype.getMessage());

                var direct = assertThrows(JedisDataException.class, () ->
                        RedisStandaloneIndexSnapshot.indexCountEvals(f.client.getPool(), 1024, keys::window, "a"));
                if (legacyReadDenied) {
                    var legacy = assertThrowsExactly(JedisDataException.class, () -> f.client.eval(
                            "return redis.call('STRLEN', KEYS[1])", List.of(firstOwner), List.of()));
                    int context = legacy.getMessage().lastIndexOf(" script: ");
                    assertTrue(context > 0);
                    assertEquals(legacy.getClass(), direct.getClass());
                    assertTrue(direct.getMessage().startsWith(legacy.getMessage().substring(0, context) + " script: "));
                    assertNotEquals(nativeWrongtype.getMessage(), direct.getMessage());
                } else assertEquals(nativeWrongtype.getMessage(), direct.getMessage());
                var generic = assertThrows(JedisDataException.class, () ->
                        f.operations.aggregateIndexCounts(1024, keys::window, "a"));
                var binary = assertThrows(JedisDataException.class, () -> f.operations.binaryIndexCounts(keys, "a"));
                assertEquals(direct.getClass(), generic.getClass());
                assertEquals(direct.getClass(), binary.getClass());
                assertEquals(direct.getMessage(), generic.getMessage());
                assertEquals(direct.getMessage(), binary.getMessage());
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());
            }
        }
    }

    @Test void nativeCountTombstoneKeyDenialCannotCertifyZeroWhenEvalIsDenied() {
        for (boolean owned : List.of(false, true)) {
            try (Fixture f = new Fixture("-eval")) {
                String tenant = owned ? "a" : null;
                var keys = f.binaryCountKeys(1024, tenant);
                f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-exists",
                        "(+exists ~" + new RedisKeyStrategy(f.prefix).entityKey("native-acl", "") + "*)");
                if (owned) {
                    assertThrows(JedisAccessControlException.class, () ->
                            f.operations.aggregateIndexCounts(1024, keys::window, tenant));
                    assertThrows(JedisAccessControlException.class, () -> f.operations.binaryIndexCounts(keys, tenant));
                } else {
                    assertTrue(f.operations.aggregateIndexCounts(1024, keys::window, null).isEmpty());
                    assertTrue(f.operations.binaryIndexCounts(keys, null).isEmpty());
                }
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());
            }
        }
    }

    private static void assertCountRoutes(Fixture f, RedisBinaryCountKeys keys, String tenant, long expected) {
        assertEquals(expected, RedisStandaloneIndexSnapshot.indexCountEvals(
                f.client.getPool(), 1024, keys::window, tenant).orElseThrow());
        assertEquals(expected, f.operations.aggregateIndexCounts(1024, keys::window, tenant).orElseThrow());
        assertEquals(expected, f.operations.binaryIndexCounts(keys, tenant).orElseThrow());
        assertEquals(0, f.client.getPool().getNumActive());
    }

    @Test void multiDeniedCountExcludesDeadWrongtypeOwnersButStillRejectsLiveWrongtypeOnRealRedis() {
        for (boolean tombstone : List.of(false, true)) {
            try (Fixture f = new Fixture("-multi")) {
                var windows = f.countWindows(1024);
                var first = windows.getFirst();
                String entity = first.entities().getFirst();
                String owner = first.owners().getFirst();
                f.admin.del(owner); f.admin.hset(owner, Map.of("invalid", "owner"));
                if (tombstone) f.admin.set(first.tombstones().getFirst(), "deleted");
                else f.admin.del(entity);
                assertEquals(1023, f.operations.aggregateIndexCounts(
                        1024, start -> windows.get(start / 512), "a").orElseThrow());
                f.admin.set(entity, "payload"); f.admin.del(first.tombstones().getFirst());
                var failure = assertThrows(JedisDataException.class, () -> f.operations.aggregateIndexCounts(
                        1024, start -> windows.get(start / 512), "a"));
                assertTrue(failure.getMessage().startsWith("WRONGTYPE"));
                assertEquals(0, f.client.getPool().getNumActive());
            }
        }
    }

    @Test void readAndEvalUserWithoutTransactionCommandsRetainsAllAggregatePaths() {
        try (Fixture f = new Fixture("-watch", "-multi", "-exec", "-unwatch")) {
            assertEquals(2, f.fallback(f::count).count());
            assertEquals(List.of("1.25", "2.75"), f.fallback(f::numeric).values());
            assertEquals(List.of(new RedisClientOperations.FacetIndexRow(0, "g", "1.25"),
                    new RedisClientOperations.FacetIndexRow(1, "g", "2.75")), f.fallback(f::facets).rows());
            assertEquals(3, f.client.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void eachNativeOnlyCommandRestrictionFallsBackWithActualRedisReplies() {
        for (String command : List.of("watch", "multi", "exec", "getrange")) {
            try (Fixture f = new Fixture("-" + command)) {
                assertEquals(List.of("1.25", "2.75"), f.fallback(f::numeric).values());
                assertEquals(2, f.fallback(f::facets).rows().size());
                if (!command.equals("watch")) assertEquals(2, f.fallback(f::count).count());
                else {
                    int before = f.client.evaluations;
                    assertEquals(2, f.count().count());
                    assertEquals(before, f.client.evaluations, "COUNT does not require WATCH");
                }
            }
        }
    }

    @Test void countLuaKeepsGetOnlyOwnershipForHealthyAndMixedWindows() {
        try (Fixture f = new Fixture("-multi", "-mget", "-getrange")) {
            assertEquals(2, f.fallback(f::count).count());
            f.admin.set(f.tombs.get(1), "deleted");
            assertEquals(1, f.fallback(f::count).count());
            f.admin.set(f.owners.getFirst(), "b");
            assertEquals(0, f.fallback(f::count).count());
            assertEquals(3, f.client.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void countLuaDeclaredRangesPreserveBoundaryLivenessAndOwnerErrors() {
        for (int size : new int[]{1, 512}) {
            for (boolean owned : List.of(false, true)) {
                try (Fixture f = new Fixture("-multi", "-mget")) {
                    var window = f.countWindows(size).getFirst();
                    var command = RedisClientOperations.indexAggregateCommand(window.entities(), window.tombstones(),
                            window.indexMetadata(), owned ? window.owners() : null, owned ? "a" : null, null, true);
                    Supplier<Long> count = () -> RedisClientOperations.parseIndexAggregateResponse(
                            f.client.eval(command.script(), command.keys(), command.arguments()), size, true)
                            .orElseThrow().count();
                    assertEquals(size, count.get().longValue());
                    String entity = window.entities().getLast();
                    String tombstone = window.tombstones().getLast();
                    String owner = window.owners().getLast();
                    f.admin.del(entity);
                    f.admin.hset(entity, Map.of("live", "entity"));
                    assertEquals(size, count.get().longValue());
                    f.admin.del(owner);
                    f.admin.hset(owner, Map.of("invalid", "owner"));
                    if (owned) {
                        assertTrue(assertThrows(JedisDataException.class, count::get)
                                .getMessage().startsWith("WRONGTYPE"));
                    } else assertEquals(size, count.get().longValue());
                    f.admin.del(entity);
                    assertEquals(size - 1, count.get().longValue());
                    f.admin.set(entity, "payload");
                    f.admin.hset(tombstone, Map.of("deleted", "1"));
                    assertEquals(size - 1, count.get().longValue());
                    f.admin.del(tombstone, owner);
                    f.admin.set(owner, "a");
                    assertEquals(size, count.get().longValue());
                    f.admin.del(window.entities().toArray(String[]::new));
                    assertEquals(0, count.get().longValue());
                    assertEquals(0, f.client.getPool().getNumActive());
                }
            }
        }
    }

    @Test void countLuaOwnerKeyDenialIsNotHiddenByCommandFallback() {
        try (Fixture f = new Fixture("-mget")) {
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "resetkeys",
                    "~" + f.prefix + "entity:*", "~" + f.prefix + "tomb:*", "~" + f.prefix + "meta:*");
            var command = RedisClientOperations.indexAggregateCommand(f.entities, f.tombs, f.meta,
                    f.owners, "a", null, true);
            assertTrue(assertThrows(JedisAccessControlException.class,
                    () -> f.client.eval(command.script(), command.keys(), command.arguments())).getMessage().contains("key"));
            assertEquals(1, f.client.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void facetLuaKeepsGetOnlyOwnershipForHealthyAndMixedWindows() {
        try (Fixture f = new Fixture("-watch", "-mget")) {
            assertEquals(2, f.fallback(f::facets).rows().size());
            assertEquals(Map.of("g", 2L), f.compactFacets().counts());
            f.admin.set(f.tombs.get(1), "deleted");
            assertEquals(1, f.fallback(f::facets).rows().size());
            assertEquals(Map.of("g", 1L), f.compactFacets().counts());
            f.admin.set(f.owners.getFirst(), "b");
            assertTrue(f.fallback(f::facets).rows().isEmpty());
            assertTrue(f.compactFacets().counts().isEmpty());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void compactFacetOwnerKeyDenialIsNotHiddenByCommandFallback() {
        try (Fixture f = new Fixture("-mget")) {
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "resetkeys",
                    "~" + f.prefix + "entity:*", "~" + f.prefix + "tomb:*", "~" + f.prefix + "meta:*");
            assertTrue(assertThrows(JedisAccessControlException.class, f::compactFacets).getMessage().contains("key"));
            assertEquals(1, f.client.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void ownerMgetSelectorKeyDenialFallsBackToQualifiedGetForCountAndFacets() {
        try (Fixture f = new Fixture("-multi", "-watch")) {
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-mget", "(+mget ~" + f.owners.getFirst() + ")");
            assertEquals(0L, f.client.eval("return redis.acl_check_cmd('MGET',unpack(KEYS)) and 1 or 0",
                    f.owners, List.of()));
            assertEquals(2, f.fallback(f::count).count());
            assertEquals(2, f.fallback(f::facets).rows().size());
            assertEquals(Map.of("g", 2L), f.compactFacets().counts());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void unownedMultiwindowCountUsesNativeTransactionsWithoutEvalOrOwnerMetadataPermissions() {
        for (int size : List.of(513, 545, 1024, 1025)) {
            try (Fixture f = new Fixture("-eval", "-getrange", "-get", "-mget", "-strlen", "-hget", "-hstrlen")) {
                var windows = f.countWindows(size);
                var first = windows.getFirst();
                f.admin.del(first.entities().getFirst(), first.owners().getFirst());
                f.admin.hset(first.entities().getFirst(), Map.of("payload", "must-not-be-read"));
                f.admin.hset(first.owners().getFirst(), Map.of("invalid", "ignored-unowned-owner"));
                long destroyed = f.client.getPool().getDestroyedCount();
                assertEquals(size, f.operations.aggregateIndexCounts(size,
                        start -> unowned(windows.get(start / 512)), null).orElseThrow());
                assertEquals(destroyed, f.client.getPool().getDestroyedCount());
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());
            }
        }
    }

    @Test void unownedMultiwindowCountDeniedTransactionsRetainWholeLuaLivenessFallback() {
        for (String command : List.of("multi", "exec")) {
            try (Fixture f = new Fixture("-" + command, "-getrange", "-get", "-mget", "-strlen", "-hget", "-hstrlen")) {
                var windows = f.countWindows(1025);
                f.admin.del(windows.getFirst().entities().getFirst());
                f.admin.hset(windows.get(1).tombstones().getFirst(), Map.of("deleted", "1"));
                f.admin.expireAt(windows.getLast().entities().getFirst(), 1);
                long destroyed = f.client.getPool().getDestroyedCount();
                int[] factories = {0};
                assertEquals(1022, f.operations.aggregateIndexCounts(1025, start -> {
                    factories[0]++;
                    return unowned(windows.get(start / 512));
                }, null).orElseThrow());
                assertEquals(3, factories[0], "Fallback must reuse every frozen key window");
                assertEquals(destroyed + 1, f.client.getPool().getDestroyedCount());
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());
            }
        }
    }

    @Test void unownedMultiwindowCountKeyAclFailureCannotBecomeAValue() {
        for (String forbidden : List.of("entity", "tomb")) {
            try (Fixture f = new Fixture()) {
                var windows = f.countWindows(1025);
                String allowed = forbidden.equals("entity") ? "tomb" : "entity";
                f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-exists",
                        "(+exists ~" + f.prefix + allowed + ":*)");
                if (forbidden.equals("entity")) {
                    long destroyed = f.client.getPool().getDestroyedCount();
                    var failure = assertThrows(JedisAccessControlException.class, () -> f.operations.aggregateIndexCounts(
                            1025, start -> unowned(windows.get(start / 512)), null));
                    assertTrue(failure.getMessage().contains("key"));
                    assertEquals(destroyed + 1, f.client.getPool().getDestroyedCount());
                } else {
                    var direct = assertThrows(JedisDataException.class, () ->
                            RedisStandaloneIndexSnapshot.indexCountEvals(f.client.getPool(), 1025,
                                    start -> unowned(windows.get(start / 512)), null));
                    long destroyed = f.client.getPool().getDestroyedCount();
                    var failure = assertThrows(JedisDataException.class, () -> f.operations.aggregateIndexCounts(
                            1025, start -> unowned(windows.get(start / 512)), null));
                    assertEquals(direct.getClass(), failure.getClass());
                    assertEquals(direct.getMessage(), failure.getMessage());
                    assertEquals(destroyed + 2, f.client.getPool().getDestroyedCount());
                }
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());
            }
        }
    }

    @Test void unownedMixedLivenessWithEvalDeniedDeclinesTheEntireCohort() {
        try (Fixture f = new Fixture("-eval", "-getrange", "-get", "-mget", "-strlen", "-hget", "-hstrlen")) {
            var windows = f.countWindows(1025);
            f.admin.set(windows.get(1).tombstones().getFirst(), "deleted");
            long destroyed = f.client.getPool().getDestroyedCount();
            int[] factories = {0};
            assertTrue(f.operations.aggregateIndexCounts(1025, start -> {
                factories[0]++;
                return unowned(windows.get(start / 512));
            }, null).isEmpty());
            assertEquals(3, factories[0]);
            assertEquals(destroyed + 2, f.client.getPool().getDestroyedCount());
            assertEquals(0, f.client.getPool().getNumActive());
            assertEquals("PONG", f.client.ping());
        }
    }

    @Test void unownedForcedLuaCountRetainsBoundaryLivenessChecksWithoutTransactions() {
        for (int size : List.of(513, 1025)) {
            try (Fixture f = new Fixture("-multi", "-exec", "-getrange", "-get", "-mget", "-strlen", "-hget", "-hstrlen")) {
                var windows = f.countWindows(size);
                // Bypass native dispatch explicitly: this remains a Lua regression after unowned native opt-in.
                Supplier<Long> count = () -> RedisStandaloneIndexSnapshot.indexCountEvals(f.client.getPool(), size,
                        start -> unowned(windows.get(start / 512)), null).orElseThrow();
                assertEquals(size, count.get().longValue());
                f.admin.del(windows.getFirst().entities().getFirst());
                f.admin.hset(windows.get(1).tombstones().getFirst(), Map.of("deleted", "1"));
                assertEquals(size - 2, count.get().longValue());
                assertEquals(0, f.client.getPool().getDestroyedCount(), "No rejected MULTI may precede forced Lua");
                assertEquals(0, f.client.getPool().getNumActive());
            }
        }
    }

    private static RedisClientOperations.IndexReadKeys unowned(RedisClientOperations.IndexReadKeys window) {
        return new RedisClientOperations.IndexReadKeys(window.entities(), window.tombstones(), window.indexMetadata(), null);
    }

    @Test void multiwindowGetrangeAndEvalUsersDoNotNeedLegacyOwnerReadCommands() {
        try (Fixture f = new Fixture("-strlen", "-mget", "-get")) {
            var windows = f.countWindows(1024);
            assertEquals(1024, f.operations.aggregateIndexCounts(1024,
                    start -> windows.get(start / 512), "a").orElseThrow());
            assertEquals(0, f.client.getPool().getDestroyedCount());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void multiwindowDeniedEvalKeepsNativeOnlyUsersWorking() {
        try (Fixture f = new Fixture("-eval", "-strlen", "-mget", "-get")) {
            var windows = f.countWindows(1024);
            long destroyed = f.client.getPool().getDestroyedCount();
            assertEquals(1024, f.operations.aggregateIndexCounts(1024,
                    start -> windows.get(start / 512), "a").orElseThrow());
            assertEquals(destroyed, f.client.getPool().getDestroyedCount(), "Healthy native primary does not attempt denied EVAL");
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void splitEvalSelectorDenialCanUseIndependentlyAuthorizedNativeCommands() {
        try (Fixture f = new Fixture("-eval", "-strlen", "-mget", "-get")) {
            var windows = f.countWindows(1024);
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user,
                    "(+eval ~" + f.prefix + "entity:*)");
            var window = windows.getFirst();
            var command = RedisClientOperations.indexAggregateCommand(window.entities(), window.tombstones(),
                    window.indexMetadata(), window.owners(), "a", null, true);
            var raw = assertThrows(JedisAccessControlException.class,
                    () -> f.client.eval(command.script(), command.keys(), command.arguments()));
            assertTrue(List.of("NOPERM No permissions to access a key",
                    "NOPERM this user has no permissions to access one of the keys used as arguments")
                    .contains(raw.getMessage()));
            long destroyed = f.client.getPool().getDestroyedCount();
            assertEquals(1024, f.operations.aggregateIndexCounts(1024,
                    start -> windows.get(start / 512), "a").orElseThrow());
            assertEquals(destroyed, f.client.getPool().getDestroyedCount(), "Native primary uses only its independently authorized keys");
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void splitEvalSelectorNeverAuthorizesForbiddenNativeEntityTombstoneOrOwnerKeys() {
        assertAll("Every forbidden key category must reject rather than return empty or a value",
                List.of("entity", "tomb", "owner").stream().map(forbidden -> (Executable) () -> {
            try (Fixture f = new Fixture("-eval")) {
                var windows = f.countWindows(1024);
                f.admin.sendCommand(Command.ACL, "SETUSER", f.user,
                        "(+eval ~" + f.prefix + "entity:*)");
                if (forbidden.equals("owner")) {
                    f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-getrange",
                            "(+getrange ~" + windows.getFirst().owners().getFirst() + ")");
                } else {
                    String allowed = forbidden.equals("entity") ? "tomb" : "entity";
                    f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-exists",
                            "(+exists ~" + f.prefix + allowed + ":*)");
                }
                OptionalLong[] observed = {null};
                assertThrows(JedisDataException.class,
                        () -> observed[0] = f.operations.aggregateIndexCounts(1024,
                                start -> windows.get(start / 512), "a"),
                        () -> "forbidden=" + forbidden + "; actual return=" + observed[0]);
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());
            }
        }));
    }

    @Test void nativeCountAllowedWrongtypeInFirstMiddleOrFinalWindowNeverFallsBack() {
        for (int position : List.of(0, 1, 2)) {
            try (Fixture f = new Fixture("-strlen", "-mget", "-get")) {
                var windows = f.countWindows(1536);
                String owner = windows.get(position).owners().getFirst();
                f.admin.del(owner); f.admin.hset(owner, Map.of("invalid", "owner"));
                long destroyed = f.client.getPool().getDestroyedCount();
                var failure = assertThrows(JedisDataException.class, () -> f.operations.aggregateIndexCounts(1536,
                        start -> windows.get(start / 512), "a"));
                assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", failure.getMessage());
                assertEquals(destroyed + 1, f.client.getPool().getDestroyedCount());
                assertEquals(0, f.client.getPool().getNumActive());
            }
        }
    }

    @Test void nativeCountBoundsUtf8OwnersAndRejectsMissingForeignAndOversizedPrefixes() {
        try (Fixture f = new Fixture("-strlen", "-mget", "-get")) {
            String tenant = "\u015firket\u4e00\u0000\r\n'\\";
            var windows = f.countWindows(1024);
            try (var pipeline = f.admin.pipelined()) {
                for (var window : windows) for (String owner : window.owners()) pipeline.set(owner, tenant);
                var owners = windows.getFirst().owners();
                pipeline.del(owners.get(0)); pipeline.set(owners.get(1), "foreign");
                pipeline.set(owners.get(2), tenant + "x");
                pipeline.set(owners.get(3), tenant + "x".repeat(2 * 1024 * 1024));
                pipeline.sync();
            }
            assertEquals(1020, f.operations.aggregateIndexCounts(1024,
                    start -> windows.get(start / 512), tenant).orElseThrow());
            assertEquals(0, f.client.getPool().getDestroyedCount());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void getrangeCapabilityDenialCannotBypassDeniedLegacyOwnerCommands() {
        try (Fixture f = new Fixture("-getrange", "-strlen", "-mget", "-get")) {
            var windows = f.countWindows(1024);
            var failure = assertThrows(JedisDataException.class, () -> f.operations.aggregateIndexCounts(1024,
                    start -> windows.get(start / 512), "a"));
            assertFalse(failure.getMessage().startsWith("NOPERM"), "Legacy Lua must retain the inner ACL error");
            assertTrue(failure.getMessage().contains("script"));
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void multiwindowCountDrainsDeniedNativeCommandsBeforeEvalAndKeepsGetOnlyUsers() {
        for (String command : List.of("multi", "exec", "getrange")) {
            try (Fixture f = new Fixture("-" + command, "-mget")) {
                var windows = f.countWindows(1024);
                long destroyed = f.client.getPool().getDestroyedCount();
                assertEquals(1024, f.operations.aggregateIndexCounts(1024,
                        start -> windows.get(start / 512), "a").orElseThrow());
                assertEquals(destroyed + 1, f.client.getPool().getDestroyedCount(), "Native denial is drained and discarded before successful EVAL");
                assertEquals(0, f.client.getPool().getNumActive());
                assertEquals("PONG", f.client.ping());
            }
        }
    }

    @Test void multiwindowOwnerGetrangeSelectorDenialUsesPerKeyLegacyReadsWithoutBroadeningKeyAccess() {
        try (Fixture f = new Fixture("-getrange")) {
            var windows = f.countWindows(1024);
            String firstOwner = windows.getFirst().owners().getFirst();
            String deniedOwner = windows.getLast().owners().getFirst();
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "(+getrange ~" + firstOwner + ")");
            assertEquals("a", f.client.getrange(firstOwner, 0, 1));
            assertThrows(JedisAccessControlException.class, () -> f.client.getrange(deniedOwner, 0, 1));
            f.admin.set(deniedOwner, "b");
            long destroyed = f.client.getPool().getDestroyedCount();
            assertEquals(1023, f.operations.aggregateIndexCounts(1024,
                    start -> windows.get(start / 512), "a").orElseThrow());
            assertEquals(destroyed + 1, f.client.getPool().getDestroyedCount(), "Native selector denial is discarded before successful per-key Lua reads");
            assertEquals(0, f.client.getPool().getNumActive());
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "resetkeys",
                    "~" + f.prefix + "entity:*", "~" + f.prefix + "tomb:*", "~" + f.prefix + "meta:*");
            OptionalLong[] observed = {null};
            assertThrows(JedisAccessControlException.class,
                    () -> observed[0] = f.operations.aggregateIndexCounts(1024,
                            start -> windows.get(start / 512), "a"),
                    () -> "owner selector reset; actual return=" + observed[0]);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void multiwindowNativeRejectionCannotHideLiveOwnerWrongtypeInLaterWindow() {
        try (Fixture f = new Fixture("-getrange")) {
            var windows = f.countWindows(1536);
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user,
                    "(+getrange ~" + f.prefix + "owner:count:0:*)",
                    "(+getrange ~" + f.prefix + "owner:count:2:*)");
            String wrongtype = windows.getLast().owners().getFirst();
            f.admin.del(wrongtype); f.admin.hset(wrongtype, Map.of("invalid", "owner"));
            long destroyed = f.client.getPool().getDestroyedCount();
            var failure = assertThrows(JedisDataException.class, () -> f.operations.aggregateIndexCounts(1536,
                    start -> windows.get(start / 512), "a"));
            assertTrue(failure.getMessage().startsWith("WRONGTYPE"));
            assertEquals(destroyed + 2, f.client.getPool().getDestroyedCount(),
                    "Discard native denial, then the failing Lua lease without retry");
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void nativeCountGetrangeDenialCannotHidePermittedWrongtypeInSameWindow() {
        try (Fixture f = new Fixture("-getrange")) {
            var windows = f.countWindows(1024);
            String deniedOwner = windows.getFirst().owners().getFirst();
            String wrongtypeOwner = windows.getFirst().owners().get(1);
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user,
                    "(+getrange ~" + wrongtypeOwner + ")");
            f.admin.del(wrongtypeOwner); f.admin.hset(wrongtypeOwner, Map.of("invalid", "owner"));
            assertThrowsExactly(JedisAccessControlException.class, () -> f.client.getrange(deniedOwner, 0, 1));
            var wrongtype = assertThrowsExactly(JedisDataException.class,
                    () -> f.client.getrange(wrongtypeOwner, 0, 1));
            assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", wrongtype.getMessage());

            long destroyed = f.client.getPool().getDestroyedCount();
            var failure = assertThrowsExactly(JedisDataException.class, () -> f.operations.aggregateIndexCounts(1024,
                    start -> windows.get(start / 512), "a"));
            assertEquals(wrongtype.getMessage(), failure.getMessage(), "Allowed GETRANGE errors retain their original reply");
            assertEquals(destroyed + 2, f.client.getPool().getDestroyedCount(), "Discard native EXECABORT, then the failing Lua lease");
            assertEquals(0, f.client.getPool().getNumActive());
            assertEquals("PONG", f.client.ping());
        }
    }

    @Test void nativeCountLegacyFallbackRetainsInnerAclFailure() {
        try (Fixture f = new Fixture("-getrange", "-strlen")) {
            var windows = f.countWindows(1024);
            String deniedOwner = windows.getFirst().owners().getFirst();
            String wrongtypeOwner = windows.getLast().owners().getFirst();
            f.admin.del(wrongtypeOwner); f.admin.hset(wrongtypeOwner, Map.of("invalid", "owner"));
            assertThrowsExactly(JedisAccessControlException.class, () -> f.client.getrange(deniedOwner, 0, 1));
            assertThrowsExactly(JedisAccessControlException.class, () -> f.client.strlen(deniedOwner));
            var wrongtype = assertThrowsExactly(JedisDataException.class,
                    () -> f.admin.getrange(wrongtypeOwner, 0, 1));
            assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", wrongtype.getMessage());
            var legacy = assertThrowsExactly(JedisDataException.class, () -> f.client.eval(
                    "return redis.call('STRLEN', KEYS[1])", List.of(deniedOwner), List.of()));
            int context = legacy.getMessage().lastIndexOf(" script: ");
            assertTrue(context > 0);
            String prefix = legacy.getMessage().substring(0, context);

            long destroyed = f.client.getPool().getDestroyedCount();
            // Verified native GETRANGE denial permits Lua, whose inner ACL error must still propagate.
            var failure = assertThrowsExactly(JedisDataException.class, () -> f.operations.aggregateIndexCounts(1024,
                    start -> windows.get(start / 512), "a"));
            assertTrue(failure.getMessage().startsWith(prefix + " script: "));
            assertNotEquals(wrongtype.getMessage(), failure.getMessage());
            assertEquals(destroyed + 2, f.client.getPool().getDestroyedCount(), "Discard native denial and the failing Lua lease without retry");
            assertEquals(0, f.client.getPool().getNumActive());
            assertEquals("PONG", f.client.ping());
        }
    }

    @Test void ownerGetSelectorKeyDenialStillFailsAfterMgetSelectorDenial() {
        try (Fixture f = new Fixture("-multi", "-watch", "-getrange")) {
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-mget", "-get",
                    "(+mget +get ~" + f.owners.getFirst() + ")");
            // Compare the legacy Lua GET error, not the distinct top-level EVAL key-admission error.
            var legacy = assertThrowsExactly(JedisDataException.class, () -> f.client.eval(
                    "return redis.call('GET', KEYS[1])", List.of(f.owners.get(1)), List.of()));
            int context = legacy.getMessage().indexOf(" script: ");
            assertTrue(context > 0);
            String prefix = legacy.getMessage().substring(0, context);
            for (Supplier<?> call : List.<Supplier<?>>of(f::count, f::facets, f::compactFacets)) {
                var failure = assertThrowsExactly(JedisDataException.class, call::get);
                assertEquals(legacy.getClass(), failure.getClass());
                assertTrue(failure.getMessage().startsWith(prefix + " script: "));
                assertEquals(0, f.client.getPool().getNumActive());
            }
        }
    }

    @Test void facetVectorExistsSuccessSkipsAclPrechecks() {
        try (Fixture f = new Fixture()) {
            String control = """
                    local engine=redis; local vectors=0
                    local redis={
                      call=engine.call,
                      pcall=function(command,...)
                        if command=='EXISTS' then
                          vectors=vectors+1; local keys={...}; local offset=(vectors-1)*2
                          assert(vectors<=2 and #keys==2)
                          assert(keys[1]==KEYS[offset+1] and keys[2]==KEYS[offset+2])
                        end
                        return engine.pcall(command,...)
                      end,
                      acl_check_cmd=function(command,...)
                        if command=='EXISTS' then error('Unexpected vector ACL precheck') end
                        return engine.acl_check_cmd(command,...)
                      end,
                      error_reply=engine.error_reply
                    }
                    local function run()
                    """;
            for (boolean owned : List.of(false, true)) for (var command : f.facetCommands(owned)) {
                String script = control + command.script() + """
                        end
                        local result=run(); assert(vectors==2); return result
                        """;
                assertEquals(f.client.eval(command.script(), command.keys(), command.arguments()),
                        f.client.eval(script, command.keys(), command.arguments()));
            }
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void facetEntityVectorDenialKeepsEarlierOwnerWrongtype() {
        try (Fixture f = new Fixture()) {
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-exists",
                    "(+exists ~" + f.entities.getFirst() + " ~" + f.prefix + "tomb:*)");
            assertEquals(0L, f.client.eval("return redis.acl_check_cmd('EXISTS',unpack(KEYS)) and 1 or 0",
                    f.entities, List.of()));
            f.admin.del(f.owners.getFirst());
            f.admin.hset(f.owners.getFirst(), Map.of("invalid", "first-owner"));
            assertFacetFailureMatchesRowOrder(f, true, "WRONGTYPE");

            f.admin.del(f.owners.getFirst()); f.admin.set(f.owners.getFirst(), "a");
            var denied = assertThrows(JedisDataException.class, () -> f.client.eval(
                    "return redis.call('EXISTS',KEYS[1])", List.of(f.entities.get(1)), List.of()));
            for (boolean owned : List.of(false, true))
                assertFacetFailureMatchesRowOrder(f, owned, scriptErrorCore(denied));
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void facetTombstoneVectorDenialKeepsEarlierOwnerWrongtypeAndSkipsDeadRows() {
        try (Fixture f = new Fixture()) {
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-exists",
                    "(+exists ~" + f.prefix + "entity:* ~" + f.tombs.getFirst() + ")");
            assertEquals(1L, f.client.eval("return redis.acl_check_cmd('EXISTS',unpack(KEYS)) and 1 or 0",
                    f.entities, List.of()));
            assertEquals(0L, f.client.eval("return redis.acl_check_cmd('EXISTS',unpack(KEYS)) and 1 or 0",
                    f.tombs, List.of()));
            f.admin.del(f.owners.getFirst());
            f.admin.hset(f.owners.getFirst(), Map.of("invalid", "first-owner"));
            assertFacetFailureMatchesRowOrder(f, true, "WRONGTYPE");

            f.admin.del(f.owners.getFirst()); f.admin.set(f.owners.getFirst(), "a");
            var denied = assertThrows(JedisDataException.class, () -> f.client.eval(
                    "return redis.call('EXISTS',KEYS[1])", List.of(f.tombs.get(1)), List.of()));
            for (boolean owned : List.of(false, true))
                assertFacetFailureMatchesRowOrder(f, owned, scriptErrorCore(denied));

            f.admin.del(f.entities.get(1), f.owners.get(1), f.meta.get(1));
            f.admin.hset(f.owners.get(1), Map.of("invalid", "dead-owner"));
            f.admin.set(f.meta.get(1), "dead-metadata");
            for (boolean owned : List.of(false, true)) for (var command : f.facetCommands(owned)) {
                assertEquals(f.client.eval(rowOrderedFacetScript(command), command.keys(), command.arguments()),
                        f.client.eval(command.script(), command.keys(), command.arguments()));
            }
            assertEquals(Map.of("g", 1L), f.compactFacets().counts());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void facetSplitExistsSelectorsPreserveOwnedAndUnownedResults() {
        for (boolean splitEntities : List.of(false, true)) try (Fixture f = new Fixture()) {
            var rules = new ArrayList<String>(List.of("SETUSER", f.user, "-exists"));
            if (splitEntities) for (String entity : f.entities) rules.add("(+exists ~" + entity + ")");
            else rules.add("(+exists ~" + f.prefix + "entity:*)");
            for (String tomb : f.tombs) rules.add("(+exists ~" + tomb + ")");
            f.admin.sendCommand(Command.ACL, rules.toArray(String[]::new));
            assertEquals(splitEntities ? 0L : 1L, f.client.eval(
                    "return redis.acl_check_cmd('EXISTS',unpack(KEYS)) and 1 or 0", f.entities, List.of()));
            assertEquals(0L, f.client.eval("return redis.acl_check_cmd('EXISTS',unpack(KEYS)) and 1 or 0",
                    f.tombs, List.of()));
            for (boolean owned : List.of(false, true)) for (var command : f.facetCommands(owned)) {
                assertEquals(f.client.eval(rowOrderedFacetScript(command), command.keys(), command.arguments()),
                        f.client.eval(command.script(), command.keys(), command.arguments()));
            }
            assertEquals(Map.of("g", 2L), f.compactFacets().counts());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void facetVectorDenialDoesNotHideLaterMetadataErrors() {
        try (Fixture f = new Fixture()) {
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-exists",
                    "(+exists ~" + f.prefix + "entity:*)",
                    "(+exists ~" + f.tombs.getFirst() + ")", "(+exists ~" + f.tombs.get(1) + ")");
            f.admin.del(f.meta.get(1)); f.admin.set(f.meta.get(1), "invalid-metadata");
            for (boolean owned : List.of(false, true)) assertFacetFailureMatchesRowOrder(f, owned, "WRONGTYPE");

            f.admin.set(f.tombs.getFirst(), "deleted");
            f.admin.del(f.owners.getFirst(), f.meta.getFirst());
            f.admin.hset(f.owners.getFirst(), Map.of("invalid", "dead-owner"));
            f.admin.set(f.meta.getFirst(), "dead-metadata");
            for (boolean owned : List.of(false, true)) assertFacetFailureMatchesRowOrder(f, owned, "WRONGTYPE");

            f.admin.del(f.meta.get(1)); f.admin.hset(f.meta.get(1), Map.of("group", "g", "amount", "2.75"));
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-hget",
                    "(+hget ~" + f.meta.getFirst() + ")");
            var denied = assertThrows(JedisDataException.class, () -> f.client.eval(
                    "return redis.call('HGET',KEYS[1],'group')", List.of(f.meta.get(1)), List.of()));
            for (boolean owned : List.of(false, true))
                assertFacetFailureMatchesRowOrder(f, owned, scriptErrorCore(denied));
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void facetSplitExistsSelectorsKeepLaterErrorsAfterSoftBudgetExhaustion() {
        for (boolean owned : List.of(false, true)) for (boolean compact : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                var rules = new ArrayList<String>(List.of("SETUSER", f.user, "-exists"));
                for (String entity : f.entities) rules.add("(+exists ~" + entity + ")");
                for (String tomb : f.tombs) rules.add("(+exists ~" + tomb + ")");
                f.admin.sendCommand(Command.ACL, rules.toArray(String[]::new));
                for (var keys : List.of(f.entities, f.tombs)) {
                    assertEquals(0L, f.client.eval("return redis.acl_check_cmd('EXISTS',unpack(KEYS)) and 1 or 0",
                            keys, List.of()));
                    for (String key : keys)
                        assertEquals(1L, f.client.eval("return redis.acl_check_cmd('EXISTS',KEYS[1]) and 1 or 0",
                                List.of(key), List.of()));
                }
                // The first row alone exceeds both reply shapes' soft budget before the later failure.
                f.admin.hset(f.meta.getFirst(), "group", "over-budget");
                var command = RedisClientOperations.facetIndexCommand(f.entities, f.tombs, f.meta,
                        owned ? f.owners : null, owned ? "a" : null, "group", compact ? null : "amount", 1, compact);
                assertEquals(List.of("cachedb-facet-phase-budget-v1", 2L, 1L),
                        f.client.eval(command.script(), command.keys(), command.arguments()));
                for (boolean wrongtype : List.of(true, false)) {
                    f.admin.del(f.meta.get(1));
                    if (wrongtype) f.admin.set(f.meta.get(1), "invalid-metadata");
                    else {
                        f.admin.hset(f.meta.get(1), Map.of("group", "g", "amount", "2.75"));
                        f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "-hget",
                                "(+hget ~" + f.meta.getFirst() + ")");
                    }
                    var expected = assertThrows(JedisDataException.class, () -> f.client.eval(
                            rowOrderedFacetScript(command), command.keys(), command.arguments()));
                    var actual = assertThrows(JedisDataException.class, () -> f.client.eval(
                            command.script(), command.keys(), command.arguments()));
                    assertEquals(expected.getClass(), actual.getClass());
                    assertEquals(scriptErrorCore(expected), scriptErrorCore(actual));
                    if (wrongtype) assertTrue(scriptErrorCore(actual).startsWith("WRONGTYPE"));
                    else {
                        var denied = assertThrows(JedisDataException.class, () -> f.client.eval(
                                "return redis.call('HGET',KEYS[1],'group')", List.of(f.meta.get(1)), List.of()));
                        assertEquals(scriptErrorCore(denied), scriptErrorCore(actual));
                    }
                    assertEquals(0, f.client.getPool().getNumActive());
                }
            }
        }
    }

    @Test void facetVectorExistsNonAclFailureReturnsOriginalErrorWithoutRowReads() {
        try (Fixture f = new Fixture()) {
            for (int failedVector : List.of(1, 2))
                for (String message : List.of("ERR vector-exists-control", "WRONGTYPE vector-exists-control")) {
                    String control = """
                            local engine=redis; local original={err='%s'}; local attempts=0; local checks=0
                            local target=%d
                            local redis={
                              call=function(...) error('Unexpected row fallback') end,
                              pcall=function(command,...)
                                attempts=attempts+1; local keys={...}; local offset=(attempts-1)*2
                                assert(command=='EXISTS' and #keys==2 and attempts<=target)
                                assert(keys[1]==KEYS[offset+1] and keys[2]==KEYS[offset+2])
                                if attempts==target then return original end
                                return engine.pcall(command,...)
                              end,
                              acl_check_cmd=function(command,...)
                                checks=checks+1; local keys={...}; local offset=(target-1)*2
                                assert(command=='EXISTS' and #keys==2)
                                assert(keys[1]==KEYS[offset+1] and keys[2]==KEYS[offset+2])
                                return true
                              end,
                              error_reply=engine.error_reply
                            }
                            local function run()
                            """.formatted(message, failedVector);
                    for (boolean owned : List.of(false, true)) for (var command : f.facetCommands(owned)) {
                        String script = control + command.script() + """
                                end
                                local result=run()
                                assert(result==original and checks==1 and attempts==target)
                                return result
                                """;
                        assertEquals(message, assertThrowsExactly(JedisDataException.class,
                                () -> f.client.eval(script, command.keys(), command.arguments())).getMessage());
                    }
                }
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void successfulOwnerMgetDoesNotRunOwnerAclPrecheckOrGetFallback() {
        try (Fixture f = new Fixture()) {
            String control = """
                    local engine=redis
                    local redis={
                      call=function(command,...)
                        if command=='GET' then error('Unexpected owner GET fallback') end
                        return engine.call(command,...)
                      end,
                      pcall=engine.pcall,
                      acl_check_cmd=function(command,...)
                        if command=='EXISTS' then return engine.acl_check_cmd(command,...) end
                        error('Unexpected owner ACL precheck')
                      end
                    }
                    """;
            for (var command : f.ownerCommands()) {
                assertEquals(f.client.eval(command.script(), command.keys(), command.arguments()),
                        f.client.eval(control + command.script(), command.keys(), command.arguments()));
            }
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void countGetrangeNonAclFailureReturnsOriginalReplyWithoutLegacyReads() {
        try (Fixture f = new Fixture()) {
            var command = f.ownerCommands().getFirst();
            for (String message : List.of("ERR owner-getrange-control", "WRONGTYPE owner-getrange-control")) {
                String control = """
                        local engine=redis; local original={err='%s'}; local checks=0
                        local redis={
                          call=function(command,...)
                            if command=='GET' or command=='STRLEN' then error('Unexpected legacy owner read') end
                            return engine.call(command,...)
                          end,
                          pcall=function(command,key,first,last)
                            assert(command=='GETRANGE' and key==KEYS[#KEYS-1] and first==0 and last==#ARGV[5])
                            return original
                          end,
                          acl_check_cmd=function(command,key,first,last)
                            assert(command=='GETRANGE' and key==KEYS[#KEYS-1] and first==0 and last==#ARGV[5])
                            checks=checks+1; return true
                          end
                        }
                        local function run()
                        """.formatted(message);
                String script = control + command.script() + """
                        end
                        local result=run()
                        assert(result==original and checks==1)
                        return result
                        """;
                assertEquals(message, assertThrowsExactly(JedisDataException.class,
                        () -> f.client.eval(script, command.keys(), command.arguments())).getMessage());
            }
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void countWithoutAclCheckApiUsesBoundedReadButNeverGuessesPermissionOnFailure() {
        try (Fixture f = new Fixture()) {
            var command = f.ownerCommands().getFirst();
            String control = """
                    local engine=redis
                    local redis={
                      call=function(command,...)
                        if command=='GET' or command=='STRLEN' then error('Unexpected legacy owner read') end
                        return engine.call(command,...)
                      end,
                      pcall=engine.pcall
                    }
                    """;
            assertEquals(f.client.eval(command.script(), command.keys(), command.arguments()),
                    f.client.eval(control + command.script(), command.keys(), command.arguments()));
            String failed = control + "redis.pcall=function(...) return {err='ERR unverified GETRANGE'} end\n"
                    + command.script();
            assertEquals("ERR unverified GETRANGE", assertThrowsExactly(JedisDataException.class,
                    () -> f.client.eval(failed, command.keys(), command.arguments())).getMessage());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void ownerMgetNonAclFailureReturnsOriginalErrorWithoutGetFallback() {
        try (Fixture f = new Fixture()) {
            for (String message : List.of("ERR owner-mget-control", "WRONGTYPE owner-mget-control")) {
                for (var command : f.ownerCommands().subList(1, 3)) {
                    String control = """
                            local engine=redis; local original={err='%s'}; local checks=0
                            local redis={
                              call=function(command,...)
                                if command=='GET' then error('Unexpected owner GET fallback') end
                                return engine.call(command,...)
                              end,
                              pcall=function(command,...)
                                if command=='EXISTS' then return engine.pcall(command,...) end
                                assert(command=='MGET'); local keys={...}
                                assert(#keys==2 and keys[1]==KEYS[#KEYS-1] and keys[2]==KEYS[#KEYS])
                                return original
                              end,
                              acl_check_cmd=function(command,...)
                                if command=='EXISTS' then return engine.acl_check_cmd(command,...) end
                                assert(command=='MGET'); local keys={...}; checks=checks+1
                                assert(#keys==2 and keys[1]==KEYS[#KEYS-1] and keys[2]==KEYS[#KEYS])
                                return true
                              end
                            }
                            local function run()
                            """.formatted(message);
                    String script = control + command.script() + """
                            end
                            local result=run()
                            assert(result==original and checks==1)
                            return result
                            """;
                    assertEquals(message, assertThrows(JedisDataException.class,
                            () -> f.client.eval(script, command.keys(), command.arguments())).getMessage());
                }
            }
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void absentAclCheckApiRetainsQualifiedGetWithoutMget() {
        try (Fixture f = new Fixture()) {
            String control = """
                    local engine=redis
                    local redis={
                      call=function(command,...)
                        if command=='EXISTS' then assert(select('#',...)==1) end
                        return engine.call(command,...)
                      end,
                      pcall=function(...) error('Unexpected vector EXISTS or owner MGET') end,
                      error_reply=engine.error_reply
                    }
                    """;
            for (boolean owned : List.of(false, true)) for (var command : f.facetCommands(owned)) {
                assertEquals(f.client.eval(command.script(), command.keys(), command.arguments()),
                        f.client.eval(control + command.script(), command.keys(), command.arguments()));
            }
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void wrongtypeAlongsideWatchNopermIsNotHiddenByLuaFallback() {
        try (Fixture f = new Fixture("-watch")) {
            f.admin.del(f.owners.get(1)); f.admin.hset(f.owners.get(1), "not", "a string");
            assertTrue(assertThrows(JedisDataException.class, f::numeric).getMessage().startsWith("WRONGTYPE"));
            assertEquals(0, f.client.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
            assertTrue(f.client.getPool().getDestroyedCount() >= 1);
        }
    }

    @Test void keyPermissionFailureCannotBecomeACommandCapabilityFallback() {
        try (Fixture f = new Fixture("-watch")) {
            f.admin.sendCommand(Command.ACL, "SETUSER", f.user, "resetkeys", "~" + f.prefix + "entity:*");
            assertTrue(assertThrows(JedisAccessControlException.class, f::numeric).getMessage().contains("key"));
            assertEquals(0, f.client.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void deniedLuaAfterVerifiedNativeRejectionPropagatesWithoutRecursion() {
        try (Fixture f = new Fixture("-watch", "-multi", "-exec", "-eval")) {
            f.client.minimumDestroyedBeforeEval = f.client.getPool().getDestroyedCount() + 1;
            assertTrue(assertThrows(JedisAccessControlException.class, f::count).getMessage().contains("eval"));
            assertEquals(1, f.client.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void rejectedUnwatchIsCleanupFailureNotAlgorithmFallback() {
        try (Fixture f = new Fixture("-unwatch")) {
            f.admin.set(f.tombs.get(1), "deleted");
            assertTrue(assertThrows(JedisAccessControlException.class, f::numeric).getMessage().contains("unwatch"));
            assertEquals(0, f.client.evaluations);
            assertEquals(0, f.client.getPool().getNumActive());
            assertTrue(f.client.getPool().getDestroyedCount() >= 1);
        }
    }

    private static String rowOrderedFacetScript(RedisClientOperations.IndexReadCommand command) {
        return """
                local engine=redis
                local redis={call=engine.call,pcall=engine.pcall,error_reply=engine.error_reply}
                """ + command.script();
    }

    private static String scriptErrorCore(JedisDataException failure) {
        String message = failure.getMessage();
        int context = message.lastIndexOf(" script: ");
        return context < 0 ? message : message.substring(0, context);
    }

    private static void assertFacetFailureMatchesRowOrder(Fixture f, boolean owned, String errorPrefix) {
        for (var command : f.facetCommands(owned)) {
            var expected = assertThrows(JedisDataException.class, () ->
                    f.client.eval(rowOrderedFacetScript(command), command.keys(), command.arguments()));
            var actual = assertThrows(JedisDataException.class, () ->
                    f.client.eval(command.script(), command.keys(), command.arguments()));
            assertEquals(expected.getClass(), actual.getClass());
            assertEquals(scriptErrorCore(expected), scriptErrorCore(actual));
            assertTrue(scriptErrorCore(actual).startsWith(errorPrefix));
        }
    }

    private static final class CountingRedis extends JedisPooled {
        int evaluations;
        long minimumDestroyedBeforeEval;
        CountingRedis(URI endpoint, String user, String password) {
            super(new HostAndPort(endpoint.getHost(), endpoint.getPort()), DefaultJedisClientConfig.builder()
                    .user(user).password(password).connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build(), config());
        }
        private static GenericObjectPoolConfig<Connection> config() {
            var config = new GenericObjectPoolConfig<Connection>();
            config.setMaxTotal(1); config.setMaxIdle(1); config.setJmxEnabled(false);
            config.setMaxWait(Duration.ofSeconds(2)); return config;
        }
        @Override public Object eval(String script, List<String> keys, List<String> arguments) {
            evaluations++;
            assertEquals(0, getPool().getNumActive(), "Native lease must be returned before borrowing for Lua");
            assertTrue(getPool().getDestroyedCount() >= minimumDestroyedBeforeEval,
                    "Verified native rejection must discard its connection before Lua");
            return super.eval(script, keys, arguments);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JedisPooled admin = new JedisPooled(URI.create(RedisTestEndpoint.uri()), 2000);
        final String prefix = "t4-acl-" + UUID.randomUUID() + ":";
        final String user = "t4_acl_" + UUID.randomUUID().toString().replace("-", "");
        final List<String> entities = List.of(prefix + "entity:1", prefix + "entity:2");
        final List<String> tombs = List.of(prefix + "tomb:1", prefix + "tomb:2");
        final List<String> meta = List.of(prefix + "meta:1", prefix + "meta:2");
        final List<String> owners = List.of(prefix + "owner:1", prefix + "owner:2");
        final List<String> countKeys = new ArrayList<>();
        CountingRedis client;
        JedisPooledOperations operations;
        boolean userCreated;
        Fixture(String... deniedCommands) {
            try {
                String password = UUID.randomUUID().toString();
                var rules = new ArrayList<String>(List.of("SETUSER", user, "reset", "on", ">" + password,
                        "~" + prefix + "*", "+@read", "+eval", "+ping", "+hello", "+client|setinfo",
                        "+watch", "+multi", "+exec", "+unwatch"));
                rules.addAll(Arrays.asList(deniedCommands));
                admin.sendCommand(Command.ACL, rules.toArray(String[]::new)); userCreated = true;
                for (int i = 0; i < 2; i++) {
                    admin.set(entities.get(i), "payload"); admin.set(owners.get(i), "a");
                    admin.hset(meta.get(i), Map.of("group", "g", "amount", i == 0 ? "1.25" : "2.75"));
                }
                client = new CountingRedis(URI.create(RedisTestEndpoint.uri()), user, password);
                operations = new JedisPooledOperations(client);
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        <T> T fallback(Supplier<T> call) {
            int before = client.evaluations;
            long destroyed = client.getPool().getDestroyedCount();
            client.minimumDestroyedBeforeEval = destroyed + 1;
            T result = call.get();
            assertEquals(before + 1, client.evaluations);
            assertEquals(destroyed + 1, client.getPool().getDestroyedCount());
            assertEquals(0, client.getPool().getNumActive());
            return result;
        }
        RedisClientOperations.IndexAggregateBatch count() {
            return operations.aggregateIndexBatch(entities, tombs, meta, owners, "a", null, true).orElseThrow();
        }
        RedisBinaryCountKeys binaryCountKeys(int candidates, String tenant) {
            var ids = java.util.stream.IntStream.range(0, candidates).mapToObj(Integer::toString).toList();
            var keys = new RedisBinaryCountKeys(new RedisKeyStrategy(prefix), "native-acl", ids, tenant);
            try (var pipeline = admin.pipelined()) {
                for (int start = 0; start < candidates; start += 512) {
                    var window = keys.window(start);
                    countKeys.addAll(window.entities()); countKeys.addAll(window.tombstones());
                    if (window.owners() != null) {
                        countKeys.addAll(window.owners());
                        for (String owner : window.owners()) pipeline.set(owner, tenant);
                    }
                }
                pipeline.sync();
            }
            return keys;
        }
        List<RedisClientOperations.IndexReadKeys> countWindows(int candidates) {
            var windows = new ArrayList<RedisClientOperations.IndexReadKeys>();
            try (var pipeline = admin.pipelined()) {
                for (int start = 0; start < candidates; start += 512) {
                    var entities = new ArrayList<String>(); var tombs = new ArrayList<String>();
                    var owners = new ArrayList<String>();
                    for (int i = start; i < Math.min(start + 512, candidates); i++) {
                        String entity = prefix + "entity:count:" + i;
                        String tomb = prefix + "tomb:count:" + i;
                        String owner = prefix + "owner:count:" + start / 512 + ":" + i;
                        countKeys.addAll(List.of(entity, tomb, owner));
                        entities.add(entity); tombs.add(tomb); owners.add(owner);
                        pipeline.set(entity, "payload"); pipeline.set(owner, "a");
                    }
                    windows.add(new RedisClientOperations.IndexReadKeys(entities, tombs,
                            Collections.nCopies(entities.size(), null), owners));
                }
                pipeline.sync();
            }
            return windows;
        }
        RedisClientOperations.IndexAggregateBatch numeric() {
            return operations.aggregateIndexBatch(entities, tombs, meta, owners, "a", "amount", false).orElseThrow();
        }
        RedisClientOperations.FacetIndexBatch facets() {
            return operations.facetIndexBatch(entities, tombs, meta, owners, "a", "group", "amount").orElseThrow();
        }
        RedisClientOperations.FacetIndexCounts compactFacets() {
            var command = RedisClientOperations.facetIndexCommand(entities, tombs, meta, owners,
                    "a", "group", null, 16L * 1024 * 1024, true);
            return RedisClientOperations.parseFacetIndexCounts(
                    client.eval(command.script(), command.keys(), command.arguments()), 2, 16L * 1024 * 1024);
        }
        List<RedisClientOperations.IndexReadCommand> facetCommands(boolean owned) {
            return List.of(RedisClientOperations.facetIndexCommand(entities, tombs, meta,
                            owned ? owners : null, owned ? "a" : null, "group", "amount", 0),
                    RedisClientOperations.facetIndexCommand(entities, tombs, meta,
                            owned ? owners : null, owned ? "a" : null, "group", null, 16L * 1024 * 1024, true));
        }
        List<RedisClientOperations.IndexReadCommand> ownerCommands() {
            return List.of(RedisClientOperations.indexAggregateCommand(entities, tombs, meta, owners, "a", null, true),
                    RedisClientOperations.facetIndexCommand(entities, tombs, meta, owners, "a", "group", "amount", 0),
                    RedisClientOperations.facetIndexCommand(entities, tombs, meta, owners, "a", "group", null,
                            16L * 1024 * 1024, true));
        }
        @Override public void close() {
            try { if (client != null) client.close(); }
            finally {
                try {
                    var keys = new ArrayList<String>(); keys.addAll(entities); keys.addAll(tombs);
                    keys.addAll(meta); keys.addAll(owners); keys.addAll(countKeys); admin.del(keys.toArray(String[]::new));
                } finally {
                    try { if (userCreated) admin.sendCommand(Command.ACL, "DELUSER", user); }
                    finally { admin.close(); }
                }
            }
        }
    }
}
