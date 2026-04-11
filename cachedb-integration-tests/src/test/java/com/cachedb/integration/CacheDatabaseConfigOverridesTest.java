package com.reactor.cachedb.integration;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfigOverrides;
import com.reactor.cachedb.core.config.PersistenceSemantics;
import com.reactor.cachedb.starter.PostgresConnectionConfig;
import com.reactor.cachedb.starter.RedisConnectionConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheDatabaseConfigOverridesTest {

    @Test
    void shouldApplyScalarAndNestedOverrides() {
        Properties properties = new Properties();
        properties.setProperty("cachedb.config.writeBehind.workerThreads", "7");
        properties.setProperty("cachedb.config.resourceLimits.defaultCachePolicy.hotEntityLimit", "222");
        properties.setProperty("cachedb.config.adminHttp.dashboardTitle", "Tuned Admin");
        properties.setProperty("cachedb.config.adminMonitoring.enabled", "true");
        properties.setProperty("cachedb.config.redisGuardrail.usedMemoryWarnBytes", "1048576");
        properties.setProperty("cachedb.config.adminMonitoring.historyMinSamples", "48");
        properties.setProperty("cachedb.config.adminMonitoring.incidentDeliveryPollTimeoutMillis", "900");
        properties.setProperty("cachedb.config.adminMonitoring.telemetryTtlSeconds", "43200");
        properties.setProperty("cachedb.config.adminMonitoring.performanceSnapshotKey", "custom:admin:performance");
        properties.setProperty("cachedb.config.adminReportJob.diagnosticsTtlSeconds", "7200");

        CacheDatabaseConfig config = CacheDatabaseConfigOverrides.apply(
                CacheDatabaseConfig.defaults(),
                properties,
                CacheDatabaseConfigOverrides.DEFAULT_PREFIX
        );

        assertEquals(7, config.writeBehind().workerThreads());
        assertEquals(222, config.resourceLimits().defaultCachePolicy().hotEntityLimit());
        assertEquals("Tuned Admin", config.adminHttp().dashboardTitle());
        assertTrue(config.adminMonitoring().enabled());
        assertEquals(1_048_576L, config.redisGuardrail().usedMemoryWarnBytes());
        assertEquals(48, config.adminMonitoring().historyMinSamples());
        assertEquals(900L, config.adminMonitoring().incidentDeliveryPollTimeoutMillis());
        assertEquals(43_200L, config.adminMonitoring().telemetryTtlSeconds());
        assertEquals("custom:admin:performance", config.adminMonitoring().performanceSnapshotKey());
        assertEquals(7_200L, config.adminReportJob().diagnosticsTtlSeconds());
    }

    @Test
    void shouldApplyProjectionRefreshOverrides() {
        Properties properties = new Properties();
        properties.setProperty("cachedb.config.runtimeCoordination.instanceId", "pod-a");
        properties.setProperty("cachedb.config.runtimeCoordination.leaderLeaseSegment", "coordination:test");
        properties.setProperty("cachedb.config.runtimeCoordination.leaderLeaseTtlMillis", "22000");
        properties.setProperty("cachedb.config.projectionRefresh.enabled", "true");
        properties.setProperty("cachedb.config.projectionRefresh.streamKey", "custom:projection-refresh");
        properties.setProperty("cachedb.config.projectionRefresh.consumerGroup", "custom-projection-group");
        properties.setProperty("cachedb.config.projectionRefresh.batchSize", "250");
        properties.setProperty("cachedb.config.projectionRefresh.claimIdleMillis", "45000");
        properties.setProperty("cachedb.config.projectionRefresh.maxStreamLength", "250000");
        properties.setProperty("cachedb.config.projectionRefresh.deadLetterEnabled", "true");
        properties.setProperty("cachedb.config.projectionRefresh.deadLetterStreamKey", "custom:projection-refresh-dlq");
        properties.setProperty("cachedb.config.projectionRefresh.deadLetterMaxLength", "12000");
        properties.setProperty("cachedb.config.projectionRefresh.maxAttempts", "5");
        properties.setProperty("cachedb.config.projectionRefresh.deadLetterWarnThreshold", "2");
        properties.setProperty("cachedb.config.projectionRefresh.deadLetterCriticalThreshold", "50");

        CacheDatabaseConfig config = CacheDatabaseConfigOverrides.apply(
                CacheDatabaseConfig.defaults(),
                properties,
                CacheDatabaseConfigOverrides.DEFAULT_PREFIX
        );

        assertEquals("pod-a", config.runtimeCoordination().instanceId());
        assertEquals("coordination:test", config.runtimeCoordination().leaderLeaseSegment());
        assertEquals(22_000L, config.runtimeCoordination().leaderLeaseTtlMillis());
        assertTrue(config.projectionRefresh().enabled());
        assertEquals("custom:projection-refresh", config.projectionRefresh().streamKey());
        assertEquals("custom-projection-group", config.projectionRefresh().consumerGroup());
        assertEquals(250, config.projectionRefresh().batchSize());
        assertEquals(45_000L, config.projectionRefresh().claimIdleMillis());
        assertEquals(250_000L, config.projectionRefresh().maxStreamLength());
        assertTrue(config.projectionRefresh().deadLetterEnabled());
        assertEquals("custom:projection-refresh-dlq", config.projectionRefresh().deadLetterStreamKey());
        assertEquals(12_000L, config.projectionRefresh().deadLetterMaxLength());
        assertEquals(5, config.projectionRefresh().maxAttempts());
        assertEquals(2L, config.projectionRefresh().deadLetterWarnThreshold());
        assertEquals(50L, config.projectionRefresh().deadLetterCriticalThreshold());
    }

    @Test
    void shouldParseStructuredPolicyOverrides() {
        Properties properties = new Properties();
        properties.setProperty(
                "cachedb.config.writeBehind.entityFlushPolicies",
                "OrderEntity,UPSERT,false,false,true,40,20,10,EXACT_SEQUENCE|CartEntity,*,true,true,true,80,40,20,LATEST_STATE"
        );
        properties.setProperty(
                "cachedb.config.redisGuardrail.entityPolicies",
                "cart,true,true,true,true,true,true,true|*,false,false,false,false,false,false,false"
        );

        CacheDatabaseConfig config = CacheDatabaseConfigOverrides.apply(
                CacheDatabaseConfig.defaults(),
                properties,
                CacheDatabaseConfigOverrides.DEFAULT_PREFIX
        );

        assertEquals(2, config.writeBehind().entityFlushPolicies().size());
        assertEquals(PersistenceSemantics.EXACT_SEQUENCE, config.writeBehind().entityFlushPolicies().get(0).effectivePersistenceSemantics());
        assertEquals("cart", config.redisGuardrail().entityPolicies().get(0).namespace());
        assertTrue(config.redisGuardrail().entityPolicies().get(0).shedQueryIndexReads());
        assertFalse(config.redisGuardrail().entityPolicies().get(1).shedPlannerLearning());
    }

    @Test
    void shouldBuildRedisAndPostgresConnectionConfigFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("cachedb.demo.redis.uri", "redis://default:welcome1@127.0.0.1:56379");
        properties.setProperty("cachedb.demo.redis.pool.maxTotal", "24");
        properties.setProperty("cachedb.demo.redis.pool.connectionTimeoutMillis", "4500");
        properties.setProperty("cachedb.demo.redis.pool.readTimeoutMillis", "9000");
        properties.setProperty("cachedb.demo.redis.pool.blockingReadTimeoutMillis", "20000");
        properties.setProperty("cachedb.demo.postgres.jdbcUrl", "jdbc:postgresql://127.0.0.1:55432/postgres");
        properties.setProperty("cachedb.demo.postgres.user", "postgres");
        properties.setProperty("cachedb.demo.postgres.password", "postgresql");
        properties.setProperty("cachedb.demo.postgres.connectTimeoutSeconds", "11");
        properties.setProperty("cachedb.demo.postgres.rewriteBatchedInserts", "false");
        properties.setProperty("cachedb.demo.postgres.additionalParameters", "stringtype=unspecified");

        RedisConnectionConfig redis = RedisConnectionConfig.fromProperties(
                properties,
                "cachedb.demo.redis",
                "redis://default:welcome1@127.0.0.1:6379"
        );
        PostgresConnectionConfig postgres = PostgresConnectionConfig.fromProperties(
                properties,
                "cachedb.demo.postgres",
                "jdbc:postgresql://127.0.0.1:5432/postgres",
                "postgres",
                "postgresql"
        );

        assertEquals(24, redis.poolMaxTotal());
        assertFalse(redis.testOnBorrow());
        assertFalse(redis.testWhileIdle());
        assertEquals(4_500, redis.connectionTimeoutMillis());
        assertEquals(9_000, redis.readTimeoutMillis());
        assertEquals(20_000, redis.blockingReadTimeoutMillis());
        assertTrue(postgres.normalizedJdbcUrl().contains("connectTimeout=11"));
        assertTrue(postgres.normalizedJdbcUrl().contains("reWriteBatchedInserts=false"));
        assertTrue(postgres.normalizedJdbcUrl().contains("stringtype=unspecified"));
    }
}
