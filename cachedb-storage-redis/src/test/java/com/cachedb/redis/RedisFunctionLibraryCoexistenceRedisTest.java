package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(45)
class RedisFunctionLibraryCoexistenceRedisTest {
    @Test
    void legacyAndCurrentLibrariesWriteTheSameUnprotectedKeysWithoutReplacingEachOther() {
        try (var fixture = new Fixture()) {
            fixture.loadBoth();
            var row = fixture.row("shared");
            assertEquals(1, fixture.upsert(fixture.legacy, row, "old", false, -1));
            fixture.assertUpsert(row, "old", 1);
            assertEquals(2, fixture.upsert(fixture.current, row, "new", false, 1));
            fixture.assertUpsert(row, "new", 2);
            assertEquals(-3, fixture.upsert(fixture.legacy, row, "stale", false, 1));
            fixture.assertUpsert(row, "new", 2);
            assertEquals(2L, fixture.redis.xlen(row.stream()));

            assertEquals(3, fixture.delete(fixture.legacy, row, false, 2));
            fixture.assertDeleted(row, 3);
            assertEquals(4, fixture.upsert(fixture.current, row, "resurrected", false, 3));
            fixture.assertUpsert(row, "resurrected", 4);
            assertNull(fixture.redis.get(row.tombstone()));
            assertEquals(5, fixture.delete(fixture.current, row, false, 4));
            fixture.assertDeleted(row, 5);

            assertEquals(6, fixture.upsert(fixture.legacy, row, "old-after-new-delete", false, 5));
            fixture.assertUpsert(row, "old-after-new-delete", 6);
            var arguments = new ArrayList<>(List.of("1", "8", "21", "0"));
            arguments.addAll(fixture.deleteArgs(row, false, 6));
            arguments.add("");
            var keys = new ArrayList<>(row.keys());
            keys.add(fixture.key("shared:hot-owner"));
            Object reply = fixture.redis.fcall(fixture.current.deleteFunctionName() + "_bulk", keys, arguments);
            var outcomes = assertInstanceOf(List.class, reply);
            assertEquals(1, outcomes.size());
            assertEquals(List.of("A", "7", "C"), assertInstanceOf(List.class, outcomes.get(0)));
            fixture.assertDeleted(row, 7);
            assertEquals(7L, fixture.redis.xlen(row.stream()));
            fixture.assertInstalled(fixture.legacy);
            fixture.assertInstalled(fixture.current);
        }
    }

    @Test
    void bothVersionsPreserveDurableCompactionAndCompletionContracts() {
        try (var fixture = new Fixture()) {
            fixture.loadBoth();
            for (var config : List.of(fixture.legacy, fixture.current)) {
                var row = fixture.row(config == fixture.legacy ? "compact-old" : "compact-new");
                assertEquals(1, fixture.upsert(config, row, "value", true, -1));
                assertEquals("1", fixture.redis.get(row.pending()));
                assertEquals("UPSERT", fixture.redis.hget(row.payload(), "type"));
                assertEquals("1", fixture.redis.hget(row.payload(), "version"));
                assertEquals(1L, fixture.redis.xlen(row.compactionStream()));
                assertEquals(0L, fixture.redis.xlen(row.stream()));

                assertEquals(2, fixture.delete(config, row, true, 1));
                assertEquals("2", fixture.redis.get(row.pending()));
                assertEquals("DELETE", fixture.redis.hget(row.payload(), "type"));
                assertEquals("2", fixture.redis.hget(row.payload(), "version"));
                assertEquals("1", fixture.redis.hget(row.stats(), "payloadCount"));
                assertEquals("1", fixture.redis.hget(row.stats(), "pendingCount"));
                assertEquals("REQUEUED", fixture.complete(config, row, 1));
                assertEquals("2", fixture.redis.get(row.pending()));
                assertEquals(2L, fixture.redis.xlen(row.compactionStream()));
                assertEquals("CLEARED", fixture.complete(config, row, 2));
                assertNull(fixture.redis.get(row.pending()));
                assertFalse(fixture.redis.exists(row.payload()));
                assertEquals("0", fixture.redis.hget(row.stats(), "payloadCount"));
                assertEquals("0", fixture.redis.hget(row.stats(), "pendingCount"));
                fixture.assertDeleted(row, 2);
                fixture.assertInstalled(config);
            }
        }
    }

    @Test
    void legacyRestartAndMissingLibraryReloadLeaveCurrentCodeAndBothMarkersUnchanged() {
        try (var fixture = new Fixture()) {
            fixture.loader(fixture.legacy).initialize();
            String oldMarker = fixture.redis.get(fixture.marker(fixture.legacy));
            String oldCode = fixture.code(fixture.legacy);
            fixture.loader(fixture.current).initialize();
            String newMarker = fixture.redis.get(fixture.marker(fixture.current));
            String newCode = fixture.code(fixture.current);

            fixture.loader(fixture.legacy).initialize();
            assertEquals(oldMarker, fixture.redis.get(fixture.marker(fixture.legacy)));
            assertEquals(oldCode, fixture.code(fixture.legacy));
            assertEquals(newMarker, fixture.redis.get(fixture.marker(fixture.current)));
            assertEquals(newCode, fixture.code(fixture.current));

            var row = fixture.row("reload");
            assertEquals(1, fixture.upsert(fixture.current, row, "before-old-reload", false, -1));
            assertEquals("OK", fixture.redis.functionDelete(fixture.legacy.libraryName()));
            var missing = assertThrows(JedisDataException.class,
                    () -> fixture.upsert(fixture.legacy, row, "missing", false, -1));
            assertTrue(missing.getMessage().contains("Function not found"), missing.getMessage());
            fixture.loader(fixture.legacy).reloadMissingLibrary();
            assertEquals(2, fixture.upsert(fixture.legacy, row, "old-reloaded", false, 1));
            assertEquals(oldMarker, fixture.redis.get(fixture.marker(fixture.legacy)));
            assertEquals(oldCode, fixture.code(fixture.legacy));
            assertEquals(newMarker, fixture.redis.get(fixture.marker(fixture.current)));
            assertEquals(newCode, fixture.code(fixture.current));

            assertEquals("OK", fixture.redis.functionDelete(fixture.current.libraryName()));
            fixture.loader(fixture.current).reloadMissingLibrary();
            assertEquals(3, fixture.upsert(fixture.current, row, "new-reloaded", false, 2));
            assertEquals(4, fixture.upsert(fixture.legacy, row, "old-after-new-reload", false, 3));
            fixture.assertUpsert(row, "old-after-new-reload", 4);
            assertEquals(oldMarker, fixture.redis.get(fixture.marker(fixture.legacy)));
            assertEquals(oldCode, fixture.code(fixture.legacy));
            assertEquals(newMarker, fixture.redis.get(fixture.marker(fixture.current)));
            assertEquals(newCode, fixture.code(fixture.current));
        }
    }

    @Test
    void sameVersionContentDriftRemainsRejectedForInitializeAndMissingFunctionReload() {
        try (var fixture = new Fixture()) {
            fixture.loadBoth();
            for (var config : List.of(fixture.legacy, fixture.current)) {
                String marker = fixture.redis.get(fixture.marker(config));
                String code = fixture.code(config);
                var drift = fixture.copy(config, code + "\n-- deliberately different fixture content\n",
                        config.libraryVersion());
                var startup = assertThrows(IllegalStateException.class,
                        () -> fixture.loader(drift).initialize());
                assertTrue(startup.getMessage().contains("content drift"), startup.getMessage());
                var reload = assertThrows(IllegalStateException.class,
                        () -> fixture.loader(drift).reloadMissingLibrary());
                assertTrue(reload.getMessage().contains("content drift"), reload.getMessage());
                assertEquals(marker, fixture.redis.get(fixture.marker(config)));
                assertEquals(code, fixture.code(config));
                assertNull(fixture.redis.get(fixture.marker(config) + ":lease"));
                fixture.assertInstalled(fixture.legacy);
                fixture.assertInstalled(fixture.current);
            }
            var row = fixture.row("after-drift");
            assertEquals(1, fixture.upsert(fixture.legacy, row, "old", false, -1));
            assertEquals(2, fixture.upsert(fixture.current, row, "new", false, 1));
            fixture.assertUpsert(row, "new", 2);
        }
    }

    @Test
    void sameLibraryDowngradeIsStillRejectedRatherThanDisablingTheGuard() {
        try (var fixture = new Fixture()) {
            fixture.loadBoth();
            var downgraded = fixture.copy(fixture.current, null, "0.18.0");
            String marker = fixture.redis.get(fixture.marker(fixture.current));
            String code = fixture.code(fixture.current);
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> fixture.loader(downgraded).initialize()).getMessage().contains("downgrade"));
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> fixture.loader(downgraded).reloadMissingLibrary()).getMessage().contains("downgrade"));
            assertEquals(marker, fixture.redis.get(fixture.marker(fixture.current)));
            assertEquals(code, fixture.code(fixture.current));
            fixture.assertInstalled(fixture.legacy);
            fixture.assertInstalled(fixture.current);
        }
    }

    @Test
    void boundedDeleteAcceptsLegacyTenantAndBothClosedProtectedFrames() {
        try (var fixture = new Fixture()) {
            fixture.loadBoth();
            var row = fixture.row("bounded-shapes");
            assertEquals(1, fixture.upsert(fixture.current, row, "retained", false, -1));
            String owner = fixture.key("bounded-owner");
            String hot = fixture.key("bounded-hot");
            fixture.redis.set(owner, "tenant-a");
            var tenantKeys = new ArrayList<>(row.keys());
            tenantKeys.add(owner);
            tenantKeys.add(hot);
            var tenantArgs = new ArrayList<>(fixture.deleteArgs(row, false, 1));
            tenantArgs.set(18, "cachedb:tenant:v1");
            tenantArgs.set(19, "tenant-a");
            var tenantFrame = new ArrayList<>(List.of("1", "9", "21", "0"));
            tenantFrame.addAll(tenantArgs);
            tenantFrame.add("");
            assertEquals(List.of(List.of("A", "2", "C")), fixture.redis.fcall(
                    fixture.current.deleteFunctionName() + "_bulk", tenantKeys, tenantFrame));
            fixture.assertDeleted(row, 2);
            for (int base : List.of(12, 13)) {
                var keys = new ArrayList<>(row.keys());
                keys.add(fixture.key("bounded-generation"));
                keys.add(fixture.key("bounded-allocator"));
                keys.add(fixture.key("bounded-counter"));
                if (base == 13) keys.add(fixture.key("bounded-membership"));
                keys.add(owner);
                keys.add(hot);
                var values = new ArrayList<>(fixture.deleteArgs(row, false, 2));
                values.set(18, "cachedb:tenant:v1");
                values.set(19, "tenant-a");
                values.addAll(List.of("cachedb:generation:v1", "9", "1", "a".repeat(64),
                        "b".repeat(64), "cachedb:version-range:v1", "10", "11"));
                if (base == 13) values.addAll(List.of("cachedb:runtime:v1", "12", UUID.randomUUID().toString()));
                var arguments = new ArrayList<>(List.of("1", Integer.toString(base),
                        Integer.toString(values.size()), "0"));
                arguments.addAll(values);
                arguments.add("");
                // Framing is accepted, but absent generation/admission must never authorize a write.
                assertEquals(List.of(List.of("R", "0", "N")), fixture.redis.fcall(
                        fixture.current.deleteFunctionName() + "_bulk", keys, arguments));
                fixture.assertDeleted(row, 2);
                assertEquals(2L, fixture.redis.xlen(row.stream()));
            }
        }
    }

    @Test
    void malformedBoundedDeleteFramesRejectTheWholeChunkBeforeAnyMutation() {
        try (var fixture = new Fixture()) {
            fixture.loadBoth();
            var row = fixture.row("malformed-bounded-shapes");
            assertEquals(1, fixture.upsert(fixture.current, row, "retained", false, -1));
            String hot = fixture.key("malformed-hot");
            for (var shape : List.of(List.of(8, 29), List.of(9, 32), List.of(12, 21),
                    List.of(12, 32), List.of(13, 21), List.of(13, 29), List.of(13, 31),
                    List.of(13, 33), List.of(14, 32))) {
                int base = shape.get(0), count = shape.get(1);
                var keys = new ArrayList<>(row.keys());
                keys.add(hot);
                keys.addAll(row.keys());
                for (int index = 8; index < base; index++) keys.add(fixture.key("malformed-extra-" + index));
                keys.add(hot);
                var arguments = new ArrayList<>(List.of("2", "8", "21", "0"));
                arguments.addAll(fixture.deleteArgs(row, false, 1));
                arguments.add("");
                arguments.addAll(List.of(Integer.toString(base), Integer.toString(count), "0"));
                var values = new ArrayList<>(fixture.deleteArgs(row, false, 1));
                while (values.size() < count) values.add("");
                arguments.addAll(values.subList(0, count));
                arguments.add("");
                var failure = assertThrows(JedisDataException.class, () -> fixture.redis.fcall(
                        fixture.current.deleteFunctionName() + "_bulk", keys, arguments));
                assertEquals("Invalid bounded delete row", failure.getMessage());
                fixture.assertUpsert(row, "retained", 1);
                assertNull(fixture.redis.get(row.tombstone()));
                assertEquals(1L, fixture.redis.xlen(row.stream()));
            }
        }
    }

    private record Row(String id, String namespace, List<String> keys) {
        String entity() { return keys.get(0); }
        String version() { return keys.get(1); }
        String tombstone() { return keys.get(2); }
        String stream() { return keys.get(3); }
        String payload() { return keys.get(4); }
        String pending() { return keys.get(5); }
        String compactionStream() { return keys.get(6); }
        String stats() { return keys.get(7); }
    }

    private static final class Fixture implements AutoCloseable {
        private final String prefix = "function_coexist_" + UUID.randomUUID().toString().replace("-", "");
        private final Set<String> ownedKeys = new LinkedHashSet<>();
        private final RedisFunctionLibrarySource source = new RedisFunctionLibrarySource();
        private final RedisFunctionsConfig legacy = RedisFunctionsConfig.builder()
                .libraryName(prefix + "_v018").upsertFunctionName(prefix + "_upsert_v018")
                .deleteFunctionName(prefix + "_delete_v018").compactionCompleteFunctionName(prefix + "_complete_v018")
                .sourceOverride(RedisFunctionsVersionContractTest.legacySource()).libraryVersion("0.18.0").build();
        private final RedisFunctionsConfig current = RedisFunctionsConfig.builder()
                .libraryName(prefix + "_v019").upsertFunctionName(prefix + "_upsert_v019")
                .deleteFunctionName(prefix + "_delete_v019").compactionCompleteFunctionName(prefix + "_complete_v019")
                .build();
        private final JedisPooled redis;

        Fixture() {
            var pool = new GenericObjectPoolConfig<Connection>();
            pool.setMaxTotal(2);
            pool.setMaxIdle(2);
            pool.setMinIdle(0);
            pool.setFairness(true);
            pool.setBlockWhenExhausted(true);
            pool.setMaxWait(Duration.ofSeconds(2));
            redis = new JedisPooled(pool, URI.create(RedisTestEndpoint.uri()), 2000, 3000);
            for (var config : List.of(legacy, current)) {
                ownedKeys.add(marker(config));
                ownedKeys.add(marker(config) + ":lease");
            }
        }

        String key(String suffix) {
            String value = prefix + ":{" + prefix + "}:" + suffix;
            ownedKeys.add(value);
            if (ownedKeys.size() > 64) throw new IllegalStateException("Fixture key budget exceeded");
            return value;
        }

        Row row(String id) {
            return new Row(id, prefix, List.of(key(id + ":entity"), key(id + ":version"),
                    key(id + ":tombstone"), key(id + ":stream"), key(id + ":payload"),
                    key(id + ":pending"), key(id + ":compaction-stream"), key(id + ":stats")));
        }

        String marker(RedisFunctionsConfig config) {
            return "cachedb:function-library:" + config.libraryName() + ":marker";
        }

        RedisFunctionLoader loader(RedisFunctionsConfig config) {
            return new RedisFunctionLoader(redis, config, source);
        }

        void loadBoth() {
            for (var config : List.of(legacy, current)) {
                assertTrue(redis.functionList(config.libraryName()).isEmpty());
                assertNull(redis.get(marker(config)));
                loader(config).initialize();
                assertInstalled(config);
            }
        }

        String code(RedisFunctionsConfig config) {
            var libraries = redis.functionListWithCode(config.libraryName());
            assertEquals(1, libraries.size());
            assertEquals(config.libraryName(), libraries.get(0).getLibraryName());
            return libraries.get(0).getLibraryCode();
        }

        void assertInstalled(RedisFunctionsConfig config) {
            String rendered = source.render(config);
            assertEquals(rendered, code(config));
            assertEquals(config.libraryVersion() + "|" + RedisFunctionsVersionContractTest.sha256(rendered),
                    redis.get(marker(config)));
            var functions = redis.functionList(config.libraryName()).get(0).getFunctions();
            var actual = new LinkedHashSet<String>();
            for (var function : functions) actual.add((String) function.get("name"));
            var expected = new LinkedHashSet<>(List.of(config.upsertFunctionName(),
                    config.deleteFunctionName(), config.compactionCompleteFunctionName()));
            if (config == current) expected.add(config.deleteFunctionName() + "_bulk");
            assertEquals(expected, actual);
        }

        RedisFunctionsConfig copy(RedisFunctionsConfig config, String override, String version) {
            return new RedisFunctionsConfig(config.enabled(), config.autoLoadLibrary(),
                    config.replaceLibraryOnLoad(), config.strictLoading(), config.libraryName(),
                    config.upsertFunctionName(), config.deleteFunctionName(), config.compactionCompleteFunctionName(),
                    config.templateResourcePath(), override, version);
        }

        long upsert(RedisFunctionsConfig config, Row row, String payload, boolean compact, long expected) {
            var arguments = List.of(payload, "1", "0", "0", "0", "0", "0", compact ? "1" : "0",
                    "coexist", "UPSERT", "CoexistRow", "coexist_rows", row.namespace(), "id", "version",
                    "deleted", "0", row.id(), "2026-10-27T00:00:00Z", "2", "id", row.id(), "value", payload,
                    Long.toString(expected), "", "", "0", "", "", "");
            return ((Number) redis.fcall(config.upsertFunctionName(), row.keys(), arguments)).longValue();
        }

        List<String> deleteArgs(Row row, boolean compact, long expected) {
            return List.of("0", "0", "0", "30", compact ? "1" : "0", "coexist", "CoexistRow",
                    "coexist_rows", row.namespace(), "id", "version", "deleted", "1", row.id(),
                    "2026-10-27T00:00:00Z", "", "", "", "", "", Long.toString(expected));
        }

        long delete(RedisFunctionsConfig config, Row row, boolean compact, long expected) {
            return ((Number) redis.fcall(config.deleteFunctionName(), row.keys(),
                    deleteArgs(row, compact, expected))).longValue();
        }

        Object complete(RedisFunctionsConfig config, Row row, long version) {
            return redis.fcall(config.compactionCompleteFunctionName(),
                    List.of(row.pending(), row.payload(), row.compactionStream(), row.stats()),
                    List.of(row.namespace(), row.id(), Long.toString(version)));
        }

        void assertUpsert(Row row, String payload, long version) {
            assertEquals(payload, redis.get(row.entity()));
            assertEquals(Long.toString(version), redis.get(row.version()));
            assertEquals(-1L, redis.pttl(row.version()));
            assertNull(redis.get(row.tombstone()));
            var entries = redis.xrange(row.stream(), new StreamEntryID("0-0"),
                    new StreamEntryID("9999999999999-0"));
            var fields = entries.get(entries.size() - 1).getFields();
            assertEquals("UPSERT", fields.get("type"));
            assertEquals(row.namespace(), fields.get("namespace"));
            assertEquals(row.id(), fields.get("id"));
            assertEquals(Long.toString(version), fields.get("version"));
            assertEquals(Long.toString(version), fields.get("col:version"));
            assertEquals(payload, fields.get("col:value"));
        }

        void assertDeleted(Row row, long version) {
            assertNull(redis.get(row.entity()));
            assertEquals(Long.toString(version), redis.get(row.version()));
            assertEquals(Long.toString(version), redis.get(row.tombstone()));
            long ttl = redis.pttl(row.tombstone());
            assertTrue(ttl > 0 && ttl <= 30000, "Finite tombstone TTL is required");
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                for (var config : List.of(legacy, current)) {
                    try {
                        if (!redis.functionList(config.libraryName()).isEmpty())
                            redis.functionDelete(config.libraryName());
                    } catch (RuntimeException problem) {
                        if (failure == null) failure = problem;
                        else failure.addSuppressed(problem);
                    }
                }
                try {
                    redis.del(ownedKeys.toArray(String[]::new));
                } catch (RuntimeException problem) {
                    if (failure == null) failure = problem;
                    else failure.addSuppressed(problem);
                }
            } finally {
                try {
                    redis.close();
                } catch (RuntimeException problem) {
                    if (failure == null) failure = problem;
                    else failure.addSuppressed(problem);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
