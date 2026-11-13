package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisStandaloneAtomicWindowRedisTest {
    @Test void globalAndTenantFacetsUseOneAtomicCallWithoutNativeWatchOrOwnerCommands() {
        for (boolean tenant : List.of(false, true)) {
            try (Fixture f = new Fixture(512)) {
                f.rejectNative = true;
                var actual = f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata,
                        tenant ? f.owners : null, tenant ? "a" : null, "group", null).orElseThrow();
                assertEquals(512, actual.rows().size());
                assertEquals(0, actual.rows().getFirst().candidate());
                assertEquals(511, actual.rows().getLast().candidate());
                assertEquals(1, f.client.evaluations);
                assertEquals(List.of("EVAL"), f.commands);
                assertEquals(0, f.client.getPool().getNumActive());
            }
        }
    }

    @Test void exactFacetWatchBoundaryIsThirtyTwoKeysNotThirtyTwoRows() {
        for (boolean tenant : List.of(false, true)) {
            int limit = tenant ? 8 : 10;
            for (int size : List.of(limit, limit + 1)) {
                try (Fixture f = new Fixture(size)) {
                    var actual = f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata,
                            tenant ? f.owners : null, tenant ? "a" : null, "group", null).orElseThrow();
                    assertEquals(size, actual.rows().size());
                    assertEquals(size == limit ? 0 : 1, f.client.evaluations);
                    assertEquals(size == limit, f.commands.contains("WATCH"));
                    if (size == limit) assertEquals(size * (tenant ? 4 : 3), f.watchedKeys);
                }
            }
        }
    }

    @Test void largeTenantCountUsesOneAtomicScriptAndGlobalCountStaysUnchanged() {
        try (Fixture f = new Fixture(512)) {
            f.rejectNative = true;
            assertEquals(512, f.count(true).count());
            assertEquals(1, f.client.evaluations);
            assertEquals(List.of("EVAL"), f.commands);
            f.reset();
            assertEquals(512, f.count(false).count());
            assertEquals(0, f.client.evaluations);
            assertEquals(List.of("MULTI", "EXISTS", "EXISTS", "EXEC"), f.commands);
        }
    }

    @Test void stockConnectionExecutesReusableOwnerTemplateWithDistinctKeysAndExactUtf8Ownership() {
        try (Fixture f = new Fixture(512)) {
            String tenant = "\u015firket";
            try (var pipeline = f.other.pipelined()) {
                for (String owner : f.owners) pipeline.set(owner, tenant);
                pipeline.sync();
            }
            f.other.set(f.owners.get(0), tenant + "x".repeat(2 * 1024 * 1024));
            f.other.set(f.owners.get(1), tenant + "\\0");
            f.other.set(f.owners.get(2), "other");
            f.other.del(f.owners.get(3));
            try (Connection connection = f.other.getPool().getResource()) {
                assertEquals(Connection.class, connection.getClass());
            }
            var actual = RedisStandaloneIndexSnapshot.aggregate(f.other.getPool(), f.entities, f.tombs,
                    f.metadata, f.owners, tenant, null, true).orElseThrow();
            assertEquals(508, actual.count());
            assertEquals(0, f.other.getPool().getNumActive());
        }
    }

    @Test void atomicAdmissionPreservesPrefixRejectionTombstonesAndNoPayloadInspection() {
        try (Fixture f = new Fixture(40)) {
            f.rejectNative = true;
            f.other.set(f.owners.get(1), "a" + "x".repeat(2 * 1024 * 1024));
            f.other.set(f.owners.get(2), "a\0");
            f.other.del(f.owners.get(3));
            f.other.set(f.owners.get(4), "b");
            f.other.set(f.tombs.get(5), "deleted");
            f.other.del(f.entities.get(6));
            f.other.del(f.metadata.get(0));
            f.other.set(f.metadata.get(0), "COUNT-star-must-not-read-metadata");
            assertEquals(34, f.count(true).count());
            assertEquals(1, f.client.evaluations);
            assertEquals(List.of("EVAL"), f.commands);
            assertFalse(f.commands.contains("WATCH"));
            f.other.del(f.metadata.get(0));
            f.other.hset(f.metadata.get(0), "group", "g0");
            f.reset(); f.rejectNative = true;
            var rows = f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata, f.owners, "a", "group", null)
                    .orElseThrow().rows();
            assertEquals(34, rows.size());
            assertFalse(rows.stream().anyMatch(row -> row.candidate() >= 1 && row.candidate() <= 6));
            assertEquals(List.of("EVAL"), f.commands);
        }
    }

    @Test void atomicFacetPreservesEmptySentinelAndExactDecimalBytes() {
        try (Fixture f = new Fixture(40)) {
            f.rejectNative = true;
            f.other.hset(f.metadata.get(0), Map.of("group", "", "amount", "0.00000000000000000001"));
            f.other.hset(f.metadata.get(1), Map.of("group", "__NULL__", "amount", "__NULL__"));
            var rows = f.ops.facetIndexBatch(f.entities, f.tombs, f.metadata, f.owners, "a", "group", "amount")
                    .orElseThrow().rows();
            assertEquals(new RedisClientOperations.FacetIndexRow(0, "", "0.00000000000000000001"), rows.get(0));
            assertEquals(new RedisClientOperations.FacetIndexRow(1, "__NULL__", "__NULL__"), rows.get(1));
            assertEquals(1, f.client.evaluations);
            assertEquals(List.of("EVAL"), f.commands);
        }
    }

    @Test void corruptAdmittedMetadataFailsBeforeUnboundedMaterialization() {
        try (Fixture f = new Fixture(40)) {
            f.rejectNative = true;
            f.other.hset(f.metadata.get(0), "group", "x".repeat(16 * 1024 * 1024 + 1));
            assertTrue(assertThrows(JedisDataException.class, () -> f.ops.facetIndexBatch(
                    f.entities, f.tombs, f.metadata, f.owners, "a", "group", null))
                    .getMessage().contains("Facet byte budget"));
            assertEquals(1, f.client.evaluations);
            assertTrue(f.client.lastScript.indexOf("'HSTRLEN'") < f.client.lastScript.indexOf("'HGET'"));
            assertTrue(f.client.lastScript.indexOf("bytes>16777216") < f.client.lastScript.indexOf("'HGET'"));
            assertEquals(List.of("EVAL"), f.commands);
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    @Test void missingMetadataWrongOwnerTypeAndTransportFailuresNeverRetry() {
        try (Fixture f = new Fixture(40)) {
            f.rejectNative = true;
            f.other.hdel(f.metadata.get(0), "group");
            assertThrows(JedisDataException.class, () -> f.ops.facetIndexBatch(
                    f.entities, f.tombs, f.metadata, f.owners, "a", "group", null));
            assertEquals(1, f.client.evaluations);
            f.other.hset(f.metadata.get(0), "group", "g0");
            f.other.del(f.owners.get(0)); f.other.hset(f.owners.get(0), "wrong", "a");
            f.reset();
            assertThrows(JedisDataException.class, () -> f.count(true));
            assertEquals(1, f.client.evaluations);
            assertEquals(List.of("EVAL"), f.commands);
            assertFalse(f.commands.contains("WATCH"));
            f.reset(); f.rejectNative = true;
            var failure = new JedisConnectionException("Injected EVAL transport failure");
            f.client.evalFailure = failure;
            assertSame(failure, assertThrows(JedisConnectionException.class, () -> f.ops.facetIndexBatch(
                    f.entities, f.tombs, f.metadata, f.owners, "a", "group", null)));
            assertEquals(1, f.client.evaluations);
            assertEquals(List.of("EVAL"), f.commands);
            assertEquals(0, f.client.getPool().getNumIdle());
            assertEquals(0, f.client.getPool().getNumActive());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> entities = new ArrayList<>(), tombs = new ArrayList<>(),
                metadata = new ArrayList<>(), owners = new ArrayList<>(), commands = new ArrayList<>();
        final JedisPooled other;
        final SnapshotClient client;
        final JedisPooledOperations ops;
        boolean rejectNative;
        int watchedKeys;
        Fixture(int size) {
            URI uri = URI.create(RedisTestEndpoint.uri());
            other = new JedisPooled(uri, 2000);
            client = new SnapshotClient(new HostAndPort(uri.getHost(), uri.getPort()), this);
            ops = new JedisPooledOperations(client);
            String prefix = "atomic-window-" + UUID.randomUUID() + ":";
            try {
                try (var pipeline = other.pipelined()) {
                    for (int i = 0; i < size; i++) {
                        entities.add(prefix + "entity:" + i); tombs.add(prefix + "tomb:" + i);
                        metadata.add(prefix + "meta:" + i); owners.add(prefix + "owner:" + i);
                        pipeline.set(entities.get(i), "payload-not-read-by-index-counts");
                        pipeline.set(owners.get(i), "a");
                        pipeline.hset(metadata.get(i), Map.of("group", "g" + i % 20, "amount", "0.125"));
                    }
                    pipeline.sync();
                }
                client.ping(); reset();
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        RedisClientOperations.IndexAggregateBatch count(boolean tenant) {
            return ops.aggregateIndexBatch(entities, tombs, metadata,
                    tenant ? owners : null, tenant ? "a" : null, null, true).orElseThrow();
        }
        void reset() { commands.clear(); watchedKeys = 0; client.evaluations = 0; rejectNative = false; }
        @Override public void close() {
            try {
                var keys = new ArrayList<String>(); keys.addAll(entities); keys.addAll(tombs);
                keys.addAll(metadata); keys.addAll(owners);
                if (!keys.isEmpty()) other.unlink(keys.toArray(String[]::new));
            } finally { client.close(); other.close(); }
        }
    }

    private static final class HookedConnection extends Connection {
        private Fixture fixture;
        HookedConnection(HostAndPort address, Fixture fixture) {
            super(address, DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000)
                    .socketTimeoutMillis(2000).build());
            this.fixture = fixture;
        }
        @Override public void sendCommand(CommandArguments args) {
            if (fixture != null) {
                String name = new String(args.getCommand().getRaw(), StandardCharsets.US_ASCII);
                if (fixture.rejectNative && Set.of("WATCH", "MULTI", "GETRANGE", "HSTRLEN", "HGET", "EXEC").contains(name))
                    throw new AssertionError("Large atomic window issued native command " + name);
                fixture.commands.add(name);
                if (name.equals("EVAL") && fixture.client != null) {
                    fixture.client.evaluations++;
                    int position = 0;
                    for (var argument : args) {
                        if (position++ == 1)
                            fixture.client.lastScript = new String(argument.getRaw(), StandardCharsets.UTF_8);
                    }
                    if (fixture.client.evalFailure != null) throw fixture.client.evalFailure;
                }
                if (name.equals("WATCH")) {
                    int arguments = 0;
                    for (var ignored : args) arguments++;
                    fixture.watchedKeys = arguments - 1;
                }
            }
            super.sendCommand(args);
        }
    }

    private static final class SnapshotClient extends JedisPooled {
        int evaluations;
        String lastScript;
        JedisConnectionException evalFailure;
        SnapshotClient(HostAndPort address, Fixture fixture) {
            super(new BasePooledObjectFactory<Connection>() {
                public Connection create() { return new HookedConnection(address, fixture); }
                public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
                @Override public void destroyObject(PooledObject<Connection> value) {
                    value.getObject().setHandlingPool(null); value.getObject().close();
                }
                @Override public boolean validateObject(PooledObject<Connection> value) {
                    return value.getObject().isConnected() && !value.getObject().isBroken();
                }
            }, config());
        }
        private static GenericObjectPoolConfig<Connection> config() {
            var config = new GenericObjectPoolConfig<Connection>();
            config.setMaxTotal(1); config.setMaxIdle(1); config.setJmxEnabled(false);
            config.setMaxWait(Duration.ofSeconds(2)); return config;
        }
    }
}
