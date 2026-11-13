package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
class RedisStandaloneBinaryFacetAclRedisTest {
    private static final int ROWS = 512;
    private static final int WINDOWS = 3;
    private static final int CANDIDATES = ROWS * WINDOWS;
    private static final String TENANT = "a";
    private static final String GROUP = "group";

    @Test void compactBinaryReadsOnlyExplicitCommandAndKeySelectors() {
        try (var f = new Fixture()) {
            assertTrue(RedisBinaryFacetKeys.supports(f.operations, f.keys));
            assertEquals(CANDIDATES, f.count());
            f.assertOutsideKeysDenied();
            assertThrowsExactly(JedisAccessControlException.class,
                    () -> f.client.set(f.key(Family.ENTITY, 0), "forbidden write"));
            f.assertPoolHealthy();
        }
    }

    @Test void evalKeyAdmissionFailureInEveryFamilyAndWindowCannotReturnPartialCounts() {
        try (var f = new Fixture()) {
            for (Family family : Family.values()) {
                for (int window = 0; window < WINDOWS; window++) {
                    f.permissions(family, null, window, true);
                    var command = f.command(window);
                    var legacy = assertThrowsExactly(JedisAccessControlException.class,
                            () -> f.client.eval(command.script(), command.keys(), command.arguments()));
                    assertTrue(legacy.getMessage().contains("key"));
                    var actual = assertThrowsExactly(JedisAccessControlException.class, f::compact);
                    assertEquals(legacy.getMessage(), actual.getMessage(), family + "/" + window);
                    f.assertPoolHealthy();
                    f.allowAll();
                    assertEquals(CANDIDATES, f.count(), "The same operations must observe restored selectors");
                }
            }
            f.assertOutsideKeysDenied();
        }
    }

    @Test void innerCommandKeyDenialsInEveryWindowPreserveLuaErrorsInsteadOfDeclining() {
        try (var f = new Fixture()) {
            for (Access access : List.of(Access.ENTITY_EXISTS, Access.TOMBSTONE_EXISTS, Access.OWNER_LENGTH,
                    Access.OWNER_VALUE, Access.INDEX_LENGTH, Access.INDEX_VALUE)) {
                for (int window = 0; window < WINDOWS; window++) {
                    f.permissions(null, access, window, true);
                    var command = f.command(window);
                    var legacy = assertThrowsExactly(JedisDataException.class,
                            () -> f.client.eval(command.script(), command.keys(), command.arguments()));
                    String prefix = luaErrorPrefix(legacy);
                    var actual = assertThrowsExactly(JedisDataException.class, f::compact);
                    assertTrue(actual.getMessage().startsWith(prefix + " script: "), access + "/" + window);
                    f.assertPoolHealthy();
                    f.allowAll();
                    assertEquals(CANDIDATES, f.count());
                }
            }
            f.assertOutsideKeysDenied();
        }
    }

    @Test void allowedOwnerMgetDoesNotRequireAdditionalGetSelectorPermissions() {
        try (var f = new Fixture()) {
            for (int window = 0; window < WINDOWS; window++) {
                f.permissions(null, Access.OWNER_GET_ONLY, window, true);
                String owner = f.key(Family.OWNER, window * ROWS);
                assertThrowsExactly(JedisAccessControlException.class, () -> f.client.get(owner));
                assertEquals(CANDIDATES, f.count());
                f.assertPoolHealthy();
            }
        }
    }

    @Test void rejectedOwnerMgetUsesOnlyQualifiedGetAndRetainsMixedLivenessChecks() {
        try (var f = new Fixture()) {
            for (int window = 0; window < WINDOWS; window++) {
                f.permissions(null, Access.OWNER_MGET_ONLY, window, true);
                var owners = f.window(window).owners();
                assertThrowsExactly(JedisAccessControlException.class,
                        () -> f.client.mget(owners.toArray(String[]::new)));
                assertEquals(TENANT, f.client.get(owners.getFirst()));
                assertEquals(CANDIDATES, f.count());
                f.assertPoolHealthy();
            }
            try (var pipeline = f.admin.pipelined()) {
                for (int window = 0; window < WINDOWS; window++) {
                    int start = window * ROWS;
                    pipeline.del(f.key(Family.ENTITY, start));
                    pipeline.set(f.key(Family.TOMBSTONE, start + 1), "deleted");
                    pipeline.set(f.key(Family.OWNER, start + 2), "foreign");
                    pipeline.del(f.key(Family.OWNER, start + 3));
                    for (int row = start; row < start + 4; row++) {
                        pipeline.del(f.key(Family.INDEX, row));
                        pipeline.set(f.key(Family.INDEX, row), "must not be read as a hash");
                    }
                }
                pipeline.sync();
            }
            assertEquals(CANDIDATES - 12, f.count());
            f.assertPoolHealthy();
            f.assertOutsideKeysDenied();
        }
    }

    @Test void verifiedEvalCommandRejectionDeclinesBinaryButExistingFallbackStillThrows() {
        try (var f = new Fixture()) {
            f.permissions(null, null, -1, false);
            var command = f.command(0);
            var legacy = assertThrowsExactly(JedisAccessControlException.class,
                    () -> f.client.eval(command.script(), command.keys(), command.arguments()));
            assertTrue(legacy.getMessage().endsWith(" has no permissions to run the 'eval' command"));
            long destroyed = f.client.getPool().getDestroyedCount();
            assertTrue(f.compact().isEmpty(), "A direct EVAL command denial is a capability decline, not an empty histogram");
            assertTrue(f.client.getPool().getDestroyedCount() > destroyed);
            f.assertPoolHealthy();
            assertTrue(f.operations.binaryFacetIndexBatches(f.binaryKeys, TENANT, GROUP, null).isEmpty());
            var window = f.window(0);
            // Exercise the existing per-window fallback API; do not implement a substitute fallback in this test.
            var failure = assertThrowsExactly(JedisAccessControlException.class, () -> f.operations.facetIndexBatch(
                    window.entities(), window.tombstones(), window.indexMetadata(), window.owners(), TENANT, GROUP, null));
            assertEquals(legacy.getMessage(), failure.getMessage());
            f.assertPoolHealthy();
            f.allowAll();
            assertEquals(CANDIDATES, f.count());
            f.assertOutsideKeysDenied();
        }
    }

    @Test void wrongtypeAndMissingMetadataInEveryWindowNeverBecomeCapabilityFallbacks() {
        try (var f = new Fixture()) {
            for (int window = 0; window < WINDOWS; window++) {
                int row = window * ROWS + (window == 1 ? ROWS / 2 : window == 2 ? ROWS - 1 : 0);
                String owner = f.key(Family.OWNER, row);
                f.admin.del(owner);
                f.admin.hset(owner, "invalid", "owner");
                var wrongtype = assertThrowsExactly(JedisDataException.class, f::compact);
                assertTrue(wrongtype.getMessage().startsWith("WRONGTYPE"));
                f.assertPoolHealthy();
                f.admin.del(owner);
                f.admin.set(owner, TENANT);
                String metadata = f.key(Family.INDEX, row);
                f.admin.hdel(metadata, GROUP);
                var missing = assertThrowsExactly(JedisDataException.class, f::compact);
                assertTrue(missing.getMessage().contains("Incomplete facet index metadata"));
                f.assertPoolHealthy();
                f.admin.hset(metadata, GROUP, "g");
                assertEquals(CANDIDATES, f.count());
            }
        }
    }

    @Test void tombstonedWindowsDoNotRemoveDeclaredOwnerOrIndexKeyAdmissionRequirements() {
        try (var f = new Fixture()) {
            for (int window = 0; window < WINDOWS; window++) {
                var tombstones = f.window(window).tombstones();
                try (var pipeline = f.admin.pipelined()) {
                    for (String key : tombstones) pipeline.set(key, "deleted");
                    pipeline.sync();
                }
                for (Family family : List.of(Family.OWNER, Family.INDEX)) {
                    f.permissions(family, null, window, true);
                    var failure = assertThrowsExactly(JedisAccessControlException.class, f::compact);
                    assertTrue(failure.getMessage().contains("key"));
                    f.assertPoolHealthy();
                }
                f.allowAll();
                assertEquals(CANDIDATES - ROWS, f.count());
                f.admin.del(tombstones.toArray(String[]::new));
            }
            assertEquals(CANDIDATES, f.count());
            f.assertOutsideKeysDenied();
        }
    }

    private static String luaErrorPrefix(JedisDataException failure) {
        int context = failure.getMessage().indexOf(" script: ");
        assertTrue(context > 0, "Expected an actual inner Lua ACL error, not top-level EVAL rejection");
        return failure.getMessage().substring(0, context);
    }

    private enum Family { ENTITY, TOMBSTONE, OWNER, INDEX }

    private enum Access {
        ENTITY_EXISTS, TOMBSTONE_EXISTS, OWNER_LENGTH, OWNER_VALUE, INDEX_LENGTH, INDEX_VALUE,
        OWNER_GET_ONLY, OWNER_MGET_ONLY
    }

    private static final class Fixture implements AutoCloseable {
        final JedisPooled admin = new JedisPooled(URI.create(RedisTestEndpoint.uri()), 2000);
        final RedisKeyStrategy keys = new RedisKeyStrategy("binary-facet-acl-" + UUID.randomUUID());
        final String user = "binary_facet_acl_" + UUID.randomUUID().toString().replace("-", "");
        final List<String> ids = IntStream.range(0, CANDIDATES)
                .mapToObj(row -> "w" + row / ROWS + ":" + row % ROWS).toList();
        final RedisBinaryFacetKeys binaryKeys = new RedisBinaryFacetKeys(keys, "rows", ids, TENANT);
        final List<String> cleanup = new ArrayList<>(4 * CANDIDATES);
        JedisPooled client;
        JedisPooledOperations operations;
        boolean userCreated;

        Fixture() {
            try {
                String password = UUID.randomUUID().toString();
                admin.sendCommand(Command.ACL, "SETUSER", user, "reset", "on", ">" + password, "resetchannels");
                userCreated = true;
                allowAll();
                try (var pipeline = admin.pipelined()) {
                    for (int row = 0; row < CANDIDATES; row++) {
                        for (Family family : Family.values()) cleanup.add(key(family, row));
                        pipeline.set(key(Family.ENTITY, row), "payload");
                        pipeline.set(key(Family.OWNER, row), TENANT);
                        pipeline.hset(key(Family.INDEX, row), Map.of(GROUP, "g"));
                    }
                    pipeline.sync();
                }
                var pool = new GenericObjectPoolConfig<Connection>();
                pool.setMaxTotal(1);
                pool.setMaxIdle(1);
                pool.setMaxWait(Duration.ofSeconds(2));
                pool.setJmxEnabled(false);
                var endpoint = URI.create(RedisTestEndpoint.uri());
                client = new JedisPooled(new HostAndPort(endpoint.getHost(), endpoint.getPort()),
                        DefaultJedisClientConfig.builder().user(user).password(password)
                                .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build(), pool);
                operations = new JedisPooledOperations(client);
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
        }

        void allowAll() { permissions(null, null, -1, true); }

        void permissions(Family deniedEvalFamily, Access deniedRead, int deniedWindow, boolean evalAllowed) {
            var rules = new ArrayList<String>(List.of("SETUSER", user, "clearselectors", "resetkeys", "-@all",
                    "+ping", "+hello", "+client|setinfo"));
            if (evalAllowed) {
                var evalKeys = new ArrayList<String>();
                for (Family family : Family.values())
                    evalKeys.addAll(patterns(family, family == deniedEvalFamily ? deniedWindow : -1));
                rules.add(selector("+eval", evalKeys));
            }
            var existsKeys = new ArrayList<>(patterns(Family.ENTITY, deniedRead == Access.ENTITY_EXISTS ? deniedWindow : -1));
            existsKeys.addAll(patterns(Family.TOMBSTONE, deniedRead == Access.TOMBSTONE_EXISTS ? deniedWindow : -1));
            rules.add(selector("+exists", existsKeys));
            rules.add(selector("+strlen", patterns(Family.OWNER, deniedRead == Access.OWNER_LENGTH ? deniedWindow : -1)));
            rules.add(selector("+mget", patterns(Family.OWNER,
                    deniedRead == Access.OWNER_VALUE || deniedRead == Access.OWNER_MGET_ONLY ? deniedWindow : -1)));
            rules.add(selector("+get", patterns(Family.OWNER,
                    deniedRead == Access.OWNER_VALUE || deniedRead == Access.OWNER_GET_ONLY ? deniedWindow : -1)));
            rules.add(selector("+hstrlen +hexists", patterns(Family.INDEX,
                    deniedRead == Access.INDEX_LENGTH ? deniedWindow : -1)));
            rules.add(selector("+hget", patterns(Family.INDEX, deniedRead == Access.INDEX_VALUE ? deniedWindow : -1)));
            admin.sendCommand(Command.ACL, rules.toArray(String[]::new));
        }

        private List<String> patterns(Family family, int deniedWindow) {
            var patterns = new ArrayList<String>(WINDOWS);
            for (int window = 0; window < WINDOWS; window++)
                if (window != deniedWindow) patterns.add(key(family, "w" + window + ":*"));
            return patterns;
        }

        private static String selector(String commands, List<String> patterns) {
            return "(" + commands + " " + String.join(" ", patterns.stream().map(key -> "~" + key).toList()) + ")";
        }

        String key(Family family, int row) { return key(family, ids.get(row)); }

        private String key(Family family, String id) {
            return switch (family) {
                case ENTITY -> keys.entityKey("rows", id);
                case TOMBSTONE -> keys.tombstoneKey("rows", id);
                case OWNER -> keys.versionKey("rows", id) + ":tenant";
                case INDEX -> keys.indexMetaKey("rows", id);
            };
        }

        RedisClientOperations.IndexReadKeys window(int window) {
            int start = window * ROWS;
            var rows = IntStream.range(start, start + ROWS).boxed().toList();
            return new RedisClientOperations.IndexReadKeys(rows.stream().map(row -> key(Family.ENTITY, row)).toList(),
                    rows.stream().map(row -> key(Family.TOMBSTONE, row)).toList(),
                    rows.stream().map(row -> key(Family.INDEX, row)).toList(),
                    rows.stream().map(row -> key(Family.OWNER, row)).toList());
        }

        RedisClientOperations.IndexReadCommand command(int window) {
            var keys = window(window);
            long budget = (RedisStandaloneIndexSnapshot.MAX_BYTES - 64L * (CANDIDATES + WINDOWS)) / (3L * WINDOWS);
            return RedisClientOperations.facetIndexCommand(keys.entities(), keys.tombstones(), keys.indexMetadata(),
                    keys.owners(), TENANT, GROUP, null, budget, true);
        }

        java.util.Optional<List<RedisClientOperations.FacetIndexCounts>> compact() {
            return operations.binaryFacetIndexCounts(binaryKeys, TENANT, GROUP);
        }

        long count() {
            var batches = compact().orElseThrow();
            assertEquals(WINDOWS, batches.size());
            long count = 0;
            for (var batch : batches) {
                assertTrue(batch.nullCandidates().isEmpty());
                assertTrue(batch.counts().size() <= 1);
                assertTrue(batch.counts().keySet().stream().allMatch("g"::equals));
                count += batch.counts().getOrDefault("g", 0L);
            }
            return count;
        }

        void assertPoolHealthy() {
            assertEquals(0, client.getPool().getNumActive());
            assertEquals("PONG", client.ping());
            assertEquals(0, client.getPool().getNumActive());
        }

        void assertOutsideKeysDenied() {
            for (Family family : Family.values()) {
                String outside = key(family, "w3:outside");
                assertThrowsExactly(JedisAccessControlException.class,
                        () -> client.eval("return redis.call('EXISTS', KEYS[1])", List.of(outside), List.of()));
                assertThrowsExactly(JedisAccessControlException.class, () -> client.exists(outside));
            }
            assertPoolHealthy();
        }

        @Override public void close() {
            try { if (client != null) client.close(); }
            finally {
                try { if (!cleanup.isEmpty()) admin.del(cleanup.toArray(String[]::new)); }
                finally {
                    try { if (userCreated) admin.sendCommand(Command.ACL, "DELUSER", user); }
                    finally { admin.close(); }
                }
            }
        }
    }
}
