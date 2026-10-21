package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.redis.RedisClientOperations;
import com.reactor.cachedb.starter.CacheDatabase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import javax.sql.DataSource;

class JacksonFreeSnapshotAutoConfigurationTest {
    @Test
    void allExplicitCodecsDoNotResolveTheMapperProvider() {
        var database = database();
        var provider = mapperProvider();
        try (var jobs =
                new CacheSnapshotAutoConfiguration()
                        .snapshotJobs(
                                mock(DataSource.class),
                                database,
                                provider,
                                new SnapshotProperties(java.util.Map.of()),
                                List.of(
                                        plan().withJsonCodec(
                                                        new JacksonFreeSnapshotTest
                                                                .SummaryCodec())))) {
            assertNotNull(jobs.typedRepository("catalog"));
            verifyNoInteractions(provider);
        }
    }

    @Test
    void mixedPlansResolveAndRetainTheConfiguredLegacyMapper() {
        var provider = mapperProvider();
        when(provider.getObject()).thenReturn(new ObjectMapper());
        var fast =
                plan().map("fast", JacksonFreeSnapshotTest.Summary.class, (root, values) -> values)
                        .withJsonCodec(new JacksonFreeSnapshotTest.SummaryCodec());
        try (var jobs =
                new CacheSnapshotAutoConfiguration()
                        .snapshotJobs(
                                mock(DataSource.class),
                                database(),
                                provider,
                                new SnapshotProperties(java.util.Map.of()),
                                List.of(plan(), fast))) {
            assertNotNull(jobs.typedRepository("catalog"));
            assertNotNull(jobs.typedRepository("fast"));
            verify(provider).getObject();
        }
    }

    private static SnapshotPlan<String, JacksonFreeSnapshotTest.Summary> plan() {
        return SnapshotPlan.streaming(
                "catalog",
                JacksonFreeSnapshotTest.source(),
                value -> value,
                JacksonFreeSnapshotTest.Summary.class,
                value -> List.of(new JacksonFreeSnapshotTest.Summary(value)));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ObjectMapper> mapperProvider() {
        return mock(ObjectProvider.class);
    }

    private static CacheDatabase database() {
        var database = mock(CacheDatabase.class);
        var redis = mock(RedisClientOperations.class);
        when(database.config()).thenReturn(CacheDatabaseConfig.defaults());
        when(database.redisOperations()).thenReturn(redis);
        when(database.backgroundRedisOperations()).thenReturn(redis);
        return database;
    }
}
