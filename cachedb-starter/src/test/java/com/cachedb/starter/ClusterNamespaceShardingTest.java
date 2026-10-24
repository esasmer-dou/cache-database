package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.redis.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;

import redis.clients.jedis.*;
import redis.clients.jedis.util.JedisClusterCRC16;

import java.time.Duration;
import java.util.*;

class ClusterNamespaceShardingTest {
    record Order(Long id, String value) {}

    record Customer(Long id, String value) {}

    @Test
    void keysPreserveSegmentsAndAtomicNamespaceSlot() {
        var keys =
                new ClusterHashTagKeyStrategy(
                        KeyspaceConfig.builder().keyPrefix("app").entitySegment("record").build());
        for (String ns : List.of("orders", "customers")) {
            int slot = JedisClusterCRC16.getSlot(keys.entityKey(ns, 1));
            for (String key :
                    List.of(
                            keys.versionKey(ns, 1),
                            keys.tombstoneKey(ns, 1),
                            keys.hotSetKey(ns),
                            keys.indexAllKey(ns),
                            keys.compactionStatsKey(ns),
                            keys.writeStreamKey("writes", ns),
                            keys.compactionStreamKey("compact", ns, 1, 4),
                            keys.entityKey(ns + ":projection:summary", 1)))
                assertEquals(slot, JedisClusterCRC16.getSlot(key));
        }
        assertNotEquals(
                JedisClusterCRC16.getSlot(keys.entityKey("orders", 1)),
                JedisClusterCRC16.getSlot(keys.entityKey("customers", 1)));
        var scoped = new ClusterHashTagKeyStrategy("app:{scope}");
        assertEquals(
                JedisClusterCRC16.getSlot(scoped.entityKey("orders", 1)),
                JedisClusterCRC16.getSlot(scoped.entityKey("customers", 1)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
    void shardedClientAndIndependentWorkerFlushBothNamespacesIntoPostgres(boolean compaction)
            throws Exception {
        String prefix = "sharding-" + UUID.randomUUID();
        String table = "sharding_" + UUID.randomUUID().toString().replace("-", "");
        String library = "test_" + UUID.randomUUID().toString().replace("-", "");
        var sql = new PGSimpleDataSource();
        sql.setURL(System.getProperty("cachedb.it.postgres.url", System.getenv()
                .getOrDefault("CACHEDB_IT_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:15435/postgres")));
        sql.setUser(System.getProperty("cachedb.it.postgres.user", System.getenv()
                .getOrDefault("CACHEDB_IT_POSTGRES_USER", "postgres")));
        sql.setPassword(System.getProperty("cachedb.it.postgres.password", System.getenv()
                .getOrDefault("CACHEDB_IT_POSTGRES_PASSWORD", "LocalFixture_7p92_test")));
        try (var connection = sql.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE "
                            + table
                            + " (id BIGINT PRIMARY KEY, value VARCHAR(100), entity_version BIGINT"
                            + " NOT NULL DEFAULT 0)");
        }
        var config =
                CacheDatabaseConfig.builder()
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                        .redisFunctions(
                                RedisFunctionsConfig.builder()
                                        .libraryName(library)
                                        .upsertFunctionName(library + "_upsert")
                                        .deleteFunctionName(library + "_delete")
                                        .compactionCompleteFunctionName(library + "_complete")
                                        .build())
                        .adminMonitoring(AdminMonitoringConfig.builder().enabled(false).build())
                        .writeBehind(
                                WriteBehindConfig.builder()
                                        .streamKey(prefix + ":stream")
                                        .compactionStreamKey(prefix + ":compact")
                                        .deadLetterStreamKey(prefix + ":dlq")
                                        .dedicatedWriteConsumerGroupEnabled(compaction)
                                        .blockTimeoutMillis(50)
                                        .idleSleepMillis(5)
                                        .build())
                        .build();
        try (var redis = client();
                var workerRedis = client()) {
            var orderMetadata = metadata(Order.class, "orders", table, Order::id);
            var customerMetadata = metadata(Customer.class, "customers", table, Customer::id);
            var orderCodec = codec(Order::id, Order::value, Order::new);
            var customerCodec = codec(Customer::id, Customer::value, Customer::new);
            try (var api =
                            new CacheDatabase(
                                    redis,
                                    sql,
                                    config.toBuilder()
                                            .runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                                            .build());
                    var worker =
                            new CacheDatabase(
                                    workerRedis,
                                    sql,
                                    config.toBuilder()
                                            .runtimeRole(CacheRuntimeRole.WORKER_ONLY)
                                            .build())) {
                api.register(orderMetadata, orderCodec, CachePolicy.defaults());
                var admin = api.admin();
                api.register(customerMetadata, customerCodec, CachePolicy.defaults());
                worker.register(orderMetadata, orderCodec, CachePolicy.defaults());
                worker.register(customerMetadata, customerCodec, CachePolicy.defaults());
                api.start();
                var keys = new ClusterHashTagKeyStrategy(config.keyspace());
                keys.registeredNamespaces(() -> List.of("orders", "customers"));
                assertEquals(
                        Set.copyOf(keys.activeStreamKeys(config.writeBehind())),
                        Set.copyOf(admin.deploymentStatus().activeStreamKeys()));
                // Simulate promotion of a primary without its function library.
                for (var pool : redis.commands().getClusterNodes().values())
                    try (var node = new Jedis(pool.getResource())) {
                        if (node.info("replication").contains("role:master"))
                            node.functionDelete(library);
                    }
                var orderReceipt =
                        api.repository(orderMetadata, orderCodec)
                                .saveWithReceipt(new Order(1L, "order"));
                var customerReceipt =
                        api.repository(customerMetadata, customerCodec)
                                .saveWithReceipt(new Customer(2L, "customer"));
                assertEquals(2L, admin.metrics().writeBehindStreamLength());
                assertEquals(compaction ? 2L : 0L, RedisBacklogEstimator.pendingCount(redis, keys));
                var batch =
                        api.writeBatch()
                                .put(new Order(3L, "same namespace"))
                                .put(new Order(4L, "same namespace"))
                                .commit();
                assertEquals(3L, admin.metrics().writeBehindStreamLength());
                worker.start();
                assertEquals(
                        "order",
                        api.repository(orderMetadata, orderCodec)
                                .findById(1L)
                                .orElseThrow()
                                .value());
                assertTrue(
                        api.awaitDurable(orderReceipt, Duration.ofSeconds(15)),
                        () -> worker.workerSnapshot().toString());
                assertTrue(
                        api.awaitDurable(customerReceipt, Duration.ofSeconds(15)),
                        () -> worker.workerSnapshot().toString());
                assertTrue(
                        api.awaitDurable(batch, Duration.ofSeconds(15)),
                        () -> worker.workerSnapshot().toString());
                assertTrue(
                        com.reactor.cachedb.core.api.DurabilityWait.await(
                                () ->
                                        RedisBacklogEstimator.estimateWriteBehindBacklog(
                                                        redis, keys, config.writeBehind())
                                                == 0L,
                                Duration.ofSeconds(5)));
                assertEquals(0L, admin.metrics().writeBehindStreamLength());
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                api.writeBatch()
                                        .put(new Order(5L, "no"))
                                        .put(new Customer(6L, "no"))
                                        .commit());
                try (var connection = sql.getConnection();
                        var statement = connection.createStatement();
                        var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(result.next());
                    assertEquals(4, result.getInt(1));
                }
            } finally {
                for (var pool : redis.commands().getClusterNodes().values())
                    try (var node = new Jedis(pool.getResource())) {
                        if (node.info("replication").contains("role:master"))
                            node.functionDelete(library);
                    }
                redis.commands().unlink("cachedb:function-library:" + library + ":marker");
                String cursor = "0";
                do {
                    var page =
                            redis.scan(
                                    cursor,
                                    new redis.clients.jedis.params.ScanParams()
                                            .match(prefix + ":*")
                                            .count(100));
                    cursor = page.getCursor();
                    for (String key : page.getResult()) redis.commands().unlink(key);
                } while (!cursor.equals("0"));
            }
        } finally {
            try (var connection = sql.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + table);
            }
        }
    }

    private JedisClusterOperations client() {
        return new JedisClusterOperations(
                new JedisCluster(
                        Set.of(new HostAndPort(System.getProperty("cachedb.redis.cluster.host","127.0.0.1"),
                                Integer.getInteger("cachedb.redis.cluster.port",17001))),
                        DefaultJedisClientConfig.builder()
                                .connectionTimeoutMillis(3000)
                                .socketTimeoutMillis(5000)
                                .hostAndPortMapper(
                                        address ->
                                                System.getProperty("cachedb.redis.cluster.host") != null ? address :
                                                new HostAndPort(
                                                        "127.0.0.1",
                                                        address.getPort() < 10000
                                                                ? address.getPort() + 10000
                                                                : address.getPort()))
                                .build()));
    }

    @Test
    @EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
    void sharedScopeAcceptsACrossEntityAtomicBatch() {
        String scope = "scope-" + UUID.randomUUID() + ":{checkout}";
        var config =
                CacheDatabaseConfig.builder()
                        .runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .keyspace(KeyspaceConfig.builder().keyPrefix(scope).build())
                        .writeBehind(
                                WriteBehindConfig.builder()
                                        .streamKey(scope + ":stream")
                                        .compactionStreamKey(scope + ":compact")
                                        .deadLetterStreamKey(scope + ":dlq")
                                        .build())
                        .build();
        try (var redis = client();
                var api = new CacheDatabase(redis, new org.h2.jdbcx.JdbcDataSource(), config)) {
            api.register(
                    metadata(Order.class, "orders", "orders", Order::id),
                    codec(Order::id, Order::value, Order::new),
                    CachePolicy.defaults());
            api.register(
                    metadata(Customer.class, "customers", "customers", Customer::id),
                    codec(Customer::id, Customer::value, Customer::new),
                    CachePolicy.defaults());
            var receipt =
                    api.writeBatch()
                            .put(new Order(1L, "order"))
                            .put(new Customer(2L, "customer"))
                            .commit();
            assertEquals(List.of(1L, 1L), receipt.versions());
            assertFalse(api.isDurable(receipt));
            assertTrue(api.idGenerator().nextSequence("orders", 4) > 0);
            String cursor = "0";
            do {
                var page =
                        redis.scan(
                                cursor,
                                new redis.clients.jedis.params.ScanParams()
                                        .match(scope + ":*")
                                        .count(100));
                cursor = page.getCursor();
                for (String key : page.getResult()) redis.commands().unlink(key);
            } while (!cursor.equals("0"));
        }
    }

    static <T> EntityMetadata<T, Long> metadata(
            Class<T> type,
            String namespace,
            String table,
            java.util.function.Function<T, Long> id) {
        return new EntityMetadata<>() {
            public String entityName() {
                return type.getSimpleName();
            }

            public String tableName() {
                return table;
            }

            public String redisNamespace() {
                return namespace;
            }

            public String idColumn() {
                return "id";
            }

            public Class<T> entityType() {
                return type;
            }

            public java.util.function.Function<T, Long> idAccessor() {
                return id;
            }

            public List<String> columns() {
                return List.of("id", "value");
            }

            public Map<String, String> columnTypes() {
                return Map.of("id", "java.lang.Long", "value", "java.lang.String");
            }

            public List<RelationDefinition> relations() {
                return List.of();
            }
        };
    }

    static <T> EntityCodec<T> codec(
            java.util.function.Function<T, Long> id,
            java.util.function.Function<T, String> value,
            java.util.function.BiFunction<Long, String, T> constructor) {
        return new EntityCodec<>() {
            public String toRedisValue(T row) {
                return id.apply(row) + "|" + value.apply(row);
            }

            public T fromRedisValue(String payload) {
                String[] parts = payload.split("\\|", 2);
                return constructor.apply(Long.valueOf(parts[0]), parts[1]);
            }

            public Map<String, Object> toColumns(T row) {
                return Map.of("id", id.apply(row), "value", value.apply(row));
            }
        };
    }
}
