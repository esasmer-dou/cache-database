package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.redis.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisMovedDataException;

@EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
class NearCacheTrackingClusterWiringTest {
    static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && deadline - System.nanoTime() > 0) Thread.sleep(2);
        assertTrue(condition.getAsBoolean(), "Cluster integration deadline exceeded");
    }
    @Test void actualMovedFromWrongMasterFlushesRootAndRequalifiesAllMastersWithSeparateAdapters() throws Exception {
        var pool = new GenericObjectPoolConfig<Connection>(); pool.setMaxTotal(8); pool.setMaxWait(Duration.ofSeconds(1));
        var clientConfig = DefaultJedisClientConfig.builder().connectionTimeoutMillis(1000).socketTimeoutMillis(1000)
                .hostAndPortMapper(address -> System.getProperty("cachedb.redis.cluster.host") != null ? address :
                        new HostAndPort("127.0.0.1", address.getPort() < 10000 ? address.getPort() + 10000 : address.getPort())).build();
        try (var client = new JedisCluster(Set.of(new HostAndPort(
                System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"),
                Integer.getInteger("cachedb.redis.cluster.port", 17001))), clientConfig, 3, Duration.ofSeconds(3), pool)) {
            var foreground = new JedisClusterOperations(client); var background = new JedisClusterOperations(client);
            String prefix = "t2-root-cluster-" + UUID.randomUUID(), ns = "evolution";
            String library = "t2_root_cluster_" + UUID.randomUUID().toString().replace("-", "");
            var keys = new ClusterHashTagKeyStrategy(prefix);
            var jdbc = new JdbcDataSource(); jdbc.setURL("jdbc:h2:mem:" + UUID.randomUUID());
            var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 10, 1048576)).build();
            // Acquire cleanup first so partial startup failures retain their original exception.
            try (AutoCloseable cleanup = () -> cleanupOwnedFixture(client, keys, prefix, ns, library);
                    var database = new CacheDatabase(foreground, background, jdbc, CacheDatabaseConfig.builder()
                    .runtimeRole(CacheRuntimeRole.CLIENT_ONLY).keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .redisFunctions(RedisFunctionsConfig.builder().libraryName(library)
                            .upsertFunctionName(library + "_upsert").deleteFunctionName(library + "_delete")
                            .compactionCompleteFunctionName(library + "_complete").build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                            .deadLetterStreamKey(prefix + ":deadletters").consumerGroup(prefix).build())
                    .queryIndex(QueryIndexConfig.builder().exactIndexEnabled(false).rangeIndexEnabled(false)
                            .prefixIndexEnabled(false).textIndexEnabled(false).build())
                    .build(), CacheRuntimeOptions.builder().nearCacheInvalidation(NearCacheInvalidationConfig.builder().mode(NearCacheInvalidationMode.SERVER_TRACKING)
                            .publishLegacyInvalidations(false).build()).build())) {
                var codec = new HotPathEvolutionRedisTest.Codec();
                database.register(HotPathEvolutionRedisTest.METADATA, codec, policy);
                var repository = database.repository(HotPathEvolutionRedisTest.METADATA, codec, policy);
                var near = database.nearCacheCoordinator();
                var store = near.register(ns, policy.nearCachePolicy());
                database.start(); await(near::trackingReady, 30000);
                long before = near.invalidationsReceived();
                repository.save(new HotPathEvolutionRedisTest.Row(1L, "old", 1));
                await(() -> near.invalidationsReceived() > before, 1000);
                assertTrue(repository.findById(1L).isPresent()); assertNotNull(store.get("1"));
                int masters = 0; JedisMovedDataException moved = null;
                for (var nodePool : client.getClusterNodes().values()) {
                    try (var node = new Jedis(nodePool.getResource())) {
                        if (!node.info("replication").contains("role:master")) continue;
                        masters++;
                        try { node.get(keys.entityKey(ns, 1L)); }
                        catch (JedisMovedDataException wrongMaster) { moved = wrongMaster; }
                    }
                }
                assertTrue(masters >= 3 && masters <= 16, "Use the real multi-master fixture");
                assertNotNull(moved, "A wrong-master GET must produce a real Redis MOVED, not a message-shaped fake");
                assertEquals(masters, near.trackingClientIds().size());
                var oldIds = near.trackingClientIds();
                long flushes = near.flushAllEvents();
                RedisTopologyChangeSource.failure(foreground, moved);
                assertTrue(near.flushAllEvents() > flushes); assertNull(store.get("1"));
                await(() -> near.trackingReady() && !oldIds.equals(near.trackingClientIds()), 30000);
                assertEquals(masters, near.trackingClientIds().size());
                assertEquals("old", repository.findById(1L).orElseThrow().name);
                database.close();
                long closedFlushes = near.flushAllEvents();
                foreground.topologyChanged(); background.topologyChanged();
                assertEquals(closedFlushes, near.flushAllEvents(), "Runtime close must unregister both topology hooks");
            }
        }
    }

    private static void cleanupOwnedFixture(JedisCluster client, ClusterHashTagKeyStrategy keys,
            String prefix, String ns, String library) {
        var failures = new ArrayList<RuntimeException>();
        for (var nodePool : client.getClusterNodes().values()) {
            try (var node = new Jedis(nodePool.getResource())) {
                if (node.info("replication").contains("role:master") && !node.functionList(library).isEmpty())
                    node.functionDelete(library);
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        String marker = "cachedb:function-library:" + library + ":marker";
        for (String key : List.of(keys.entityKey(ns, 1L), keys.versionKey(ns, 1L),
                keys.tombstoneKey(ns, 1L), keys.hotSetKey(ns), keys.writeStreamKey(prefix + ":writes", ns),
                marker, marker + ":lease")) {
            try {
                client.del(key);
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("Owned cluster fixture cleanup failed", failures.get(0));
            for (int i = 1; i < failures.size(); i++) failure.addSuppressed(failures.get(i));
            throw failure;
        }
    }
}
