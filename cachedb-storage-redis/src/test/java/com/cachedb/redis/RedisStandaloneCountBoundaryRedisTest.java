package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.query.AggregateSpec;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.exceptions.JedisDataException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisStandaloneCountBoundaryRedisTest {
    @Test @Timeout(30) void aboveNativeCapDirectDenialRetainsGenericAndBinaryRepositoryWindowFallback() {
        try (var admin = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(admin, true);
                var scope = CacheTenantContext.withTenant("a")) {
            var rows = IntStream.range(0, 10_001).mapToObj(id -> new RedisT4AggregationRedisTest.Row(
                    (long) id, "a", "x", null, "ok")).toList();
            for (int start = 0; start < rows.size(); start += 512) {
                fixture.seed(rows.subList(start, Math.min(start + 512, rows.size())));
            }
            assertFalse(admin.exists(fixture.keys.indexDegradedKey("t4_rows")));
            assertEquals(10_001L, admin.scard(fixture.keys.indexExactKey(
                    "t4_rows", "tenant", RedisIndexKeySpace.encodeKeyPart("a"))));
            String user = "owned_count_boundary_" + UUID.randomUUID().toString().replace("-", "");
            String password = UUID.randomUUID().toString();
            URI uri = URI.create(RedisTestEndpoint.uri());
            try {
                admin.sendCommand(Command.ACL, "SETUSER", user, "reset", "on", ">" + password,
                        "~" + fixture.keys.namespacePrefix("t4_rows") + ":*", "+@read", "+ping", "+hello",
                        "+client|setinfo", "+watch", "+multi", "+exec", "+unwatch", "-eval");
                for (boolean binary : List.of(false, true)) {
                    var names = new ArrayList<String>();
                    int[] created = {0};
                    try (var client = new Client(new HostAndPort(uri.getHost(), uri.getPort()),
                            DefaultJedisClientConfig.builder().user(user).password(password)
                                    .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build(), names, created)) {
                        var operations = new JedisPooledOperations(client);
                        int[] genericPhases = {0};
                        RedisClientOperations selected = binary ? operations : new RedisClientOperations() {
                            @Override public UnifiedJedis commands() { return client; }
                            @Override public OptionalLong aggregateIndexCounts(int candidates,
                                    IntFunction<IndexReadKeys> windows, String tenant) {
                                genericPhases[0]++;
                                assertEquals(10_001, candidates);
                                return operations.aggregateIndexCounts(candidates, windows, tenant);
                            }
                            @Override public Optional<IndexAggregateBatch> aggregateIndexBatch(List<String> entities,
                                    List<String> tombs, List<String> metadata, List<String> owners,
                                    String tenant, String field, boolean count) {
                                assertTrue(entities.size() <= 512);
                                return operations.aggregateIndexBatch(entities, tombs, metadata, owners, tenant, field, count);
                            }
                        };
                        var repository = RedisStandaloneCountFacetPipelineRedisTest.repository(selected, fixture);
                        assertEquals(10_001, repository.aggregate(AggregateSpec.count(null)).count());
                        assertEquals(binary ? 0 : 1, genericPhases[0]);
                        assertEquals(binary ? 41 : 40, Collections.frequency(names, "EVAL"),
                                "Binary candidate discovery adds one denied guarded-membership EVAL");
                        assertEquals(20, Collections.frequency(names, "MULTI"));
                        assertEquals(10_001, Collections.frequency(names, "GETRANGE"));
                        assertFalse(names.contains("GET"));
                        assertFalse(names.contains("MGET"));
                        assertFalse(names.contains("STRLEN"));
                        assertEquals(0, client.getPool().getNumActive());
                        assertEquals(1, client.getPool().getMaxTotal());
                        admin.del(fixture.owner(10_000));
                        admin.rpush(fixture.owner(10_000), "wrong-type");
                        try {
                            var failure = assertThrows(JedisDataException.class,
                                    () -> repository.aggregate(AggregateSpec.count(null)));
                            assertTrue(failure.getMessage().contains("WRONGTYPE"));
                            assertEquals(0, client.getPool().getNumActive());
                        } finally {
                            admin.del(fixture.owner(10_000));
                            admin.set(fixture.owner(10_000), "a");
                        }
                    }
                }
            } finally {
                admin.sendCommand(Command.ACL, "DELUSER", user);
            }
        }
    }

    private static final class RecordingConnection extends Connection {
        private List<String> names;
        RecordingConnection(HostAndPort address, JedisClientConfig config, List<String> names) {
            super(address, config); this.names = names;
        }
        @Override public void sendCommand(CommandArguments args) {
            if (names != null) names.add(new String(args.getCommand().getRaw(), StandardCharsets.US_ASCII));
            super.sendCommand(args);
        }
    }

    private static final class Client extends JedisPooled {
        Client(HostAndPort address, JedisClientConfig config, List<String> names, int[] created) {
            super(new BasePooledObjectFactory<Connection>() {
                public Connection create() { created[0]++; return new RecordingConnection(address, config, names); }
                public PooledObject<Connection> wrap(Connection connection) { return new DefaultPooledObject<>(connection); }
                @Override public void destroyObject(PooledObject<Connection> object) {
                    object.getObject().setHandlingPool(null); object.getObject().close();
                }
                @Override public boolean validateObject(PooledObject<Connection> object) {
                    return object.getObject().isConnected() && !object.getObject().isBroken();
                }
            }, poolConfig());
        }
        private static GenericObjectPoolConfig<Connection> poolConfig() {
            var config = new GenericObjectPoolConfig<Connection>();
            config.setMaxTotal(1); config.setMaxIdle(1); config.setJmxEnabled(false);
            config.setMaxWait(Duration.ofSeconds(2));
            return config;
        }
    }

}
