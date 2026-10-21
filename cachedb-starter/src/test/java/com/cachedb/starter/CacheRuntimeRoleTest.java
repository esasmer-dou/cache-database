package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.config.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import redis.clients.jedis.JedisPooled;

import java.util.*;
import java.util.stream.Collectors;

class CacheRuntimeRoleTest {
    @Test
    void configPreservesRoleInBuildersAndOverrides() {
        var config =
                CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY).build();
        assertEquals(CacheRuntimeRole.CLIENT_ONLY, config.toBuilder().build().runtimeRole());
        assertEquals(
                CacheRuntimeRole.CLIENT_ONLY,
                CacheDatabaseConfigOverrides.apply(config, new Properties(), "cachedb.config.")
                        .runtimeRole());
        assertEquals(CacheRuntimeRole.FULL, CacheDatabaseConfig.defaults().runtimeRole());
    }

    @Test
    @EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
    void clientStartsNoWorkersAndNeverConstructsASqlFlusher() {
        String prefix = "role-test-" + UUID.randomUUID();
        var config =
                CacheDatabaseConfig.builder()
                        .runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                        .writeBehind(
                                WriteBehindConfig.builder()
                                        .streamKey(prefix + ":stream")
                                        .compactionStreamKey(prefix + ":compact")
                                        .deadLetterStreamKey(prefix + ":dlq")
                                        .build())
                        .writeBehindFlusherFactory(
                                (source, registry, writeBehind, performance) -> {
                                    throw new AssertionError(
                                            "CLIENT_ONLY must not create a SQL flusher");
                                })
                        .build();
        var sql = new JdbcDataSource();
        var before = threads();
        try (var redis = new JedisPooled("redis://127.0.0.1:16383");
                var database = new CacheDatabase(redis, sql, config)) {
            database.start();
            assertEquals(before, threads());
            var metadata =
                    ClusterNamespaceShardingTest.metadata(
                            ClusterNamespaceShardingTest.Order.class,
                            "orders",
                            "orders",
                            ClusterNamespaceShardingTest.Order::id);
            var codec =
                    ClusterNamespaceShardingTest.codec(
                            ClusterNamespaceShardingTest.Order::id,
                            ClusterNamespaceShardingTest.Order::value,
                            ClusterNamespaceShardingTest.Order::new);
            var repository = database.repository(metadata, codec);
            var receipt =
                    repository.saveWithReceipt(
                            new ClusterNamespaceShardingTest.Order(1L, "accepted"));
            assertEquals("accepted", repository.findById(1L).orElseThrow().value());
            assertFalse(database.isDurable(receipt));
            assertEquals(before, threads());
            assertEquals(0, database.workerSnapshot().flushedCount());
            assertEquals(CacheRuntimeRole.CLIENT_ONLY, database.config().runtimeRole());
        }
    }

    private Set<String> threads() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("cachedb-"))
                .collect(Collectors.toSet());
    }
}
