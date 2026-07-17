package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.CachePolicyCatalog;
import com.reactor.cachedb.core.cache.EntityHotPolicyCompositeOperator;
import com.reactor.cachedb.core.cache.EntityHotPolicyMode;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.mssql.MssqlWriteBehindFlusher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheDbSpringPropertiesTest {

    @Test
    void shouldExposeSplitRedisPoolsAsStarterDefaults() {
        CacheDbSpringProperties properties = new CacheDbSpringProperties();

        assertEquals(CacheDbSpringProperties.Profile.DEFAULT, properties.getProfile());
        assertEquals("redis://127.0.0.1:6379", properties.getRedis().getUri());
        assertEquals(64, properties.getRedis().getPool().getMaxTotal());
        assertEquals(16, properties.getRedis().getPool().getMaxIdle());
        assertEquals(4, properties.getRedis().getPool().getMinIdle());
        assertFalse(properties.getRedis().getPool().isTestWhileIdle());
        assertEquals(2_000, properties.getRedis().getPool().getConnectionTimeoutMillis());
        assertEquals(5_000, properties.getRedis().getPool().getReadTimeoutMillis());
        assertEquals(15_000, properties.getRedis().getPool().getBlockingReadTimeoutMillis());

        assertTrue(properties.getRedis().getBackground().isEnabled());
        assertEquals("redis://127.0.0.1:6379", properties.getRedis().getBackground().resolveUri(properties.getRedis().getUri()));
        assertEquals(24, properties.getRedis().getBackground().getPool().getMaxTotal());
        assertEquals(8, properties.getRedis().getBackground().getPool().getMaxIdle());
        assertEquals(2, properties.getRedis().getBackground().getPool().getMinIdle());
        assertFalse(properties.getRedis().getBackground().getPool().isTestWhileIdle());
        assertEquals(2_000, properties.getRedis().getBackground().getPool().getConnectionTimeoutMillis());
        assertEquals(10_000, properties.getRedis().getBackground().getPool().getReadTimeoutMillis());
        assertEquals(30_000, properties.getRedis().getBackground().getPool().getBlockingReadTimeoutMillis());
        assertTrue(properties.getRuntime().isAppendInstanceIdToConsumerNames());
        assertTrue(properties.getRuntime().isLeaderLeaseEnabled());
        assertEquals("coordination:leader", properties.getRuntime().getLeaderLeaseSegment());
        assertEquals(15_000L, properties.getRuntime().getLeaderLeaseTtlMillis());
        assertEquals(5_000L, properties.getRuntime().getLeaderLeaseRenewIntervalMillis());
        assertTrue(properties.getScheduledWarm().isEnabled());
        assertEquals(2, properties.getScheduledWarm().getSchedulerPoolSize());
        assertEquals(1, properties.getScheduledWarm().getHeartbeatThreads());
        assertEquals("cachedb-scheduled-warm-", properties.getScheduledWarm().getThreadNamePrefix());
        assertEquals("coordination:scheduled-warm", properties.getScheduledWarm().getLockKeySegment());
        assertEquals(10_000L, properties.getScheduledWarm().getShutdownAwaitMillis());
        assertTrue(properties.getRegistration().isEnabled());
        assertEquals(CacheDbSpringProperties.RegistrationSource.METADATA_ONLY, properties.getRegistration().getSource());
        assertTrue(properties.getRegistration().isFailOnUnknownEntity());
        assertEquals(CacheDbSpringProperties.SqlProvider.POSTGRES, properties.getSql().getProvider());
        assertEquals(5_000, properties.getSql().getMssql().getLockTimeoutMillis());
        assertEquals(10, properties.getSql().getMssql().getQueryTimeoutSeconds());
        assertEquals(
                CacheDbSpringProperties.TransactionIsolation.SERIALIZABLE,
                properties.getSql().getMssql().getTransactionIsolation()
        );
        assertTrue(properties.getSql().getMssql().isRestoreLockTimeoutAfterTransaction());
        assertTrue(properties.getAdmin().isEnabled());
        assertFalse(properties.getAdmin().isHttpEnabled());
        assertFalse(properties.getAdmin().isAuthEnabled());
        assertEquals("Authorization", properties.getAdmin().getAuthHeaderName());
        assertEquals(128, properties.getAdmin().getRequestQueueCapacity());
        assertEquals(2, properties.getAdmin().getBackgroundWorkerThreads());
        assertEquals(32, properties.getAdmin().getBackgroundQueueCapacity());
        assertEquals(1_048_576, properties.getAdmin().getMaxRequestBodyBytes());
        assertEquals(86_400, properties.getAdmin().getJobStatusTtlSeconds());
    }

    @Test
    void shouldBuildDeclarativePerEntityCompositePolicy() {
        CacheDbSpringProperties properties = new CacheDbSpringProperties();
        CacheDbSpringProperties.EntityPolicyProperties orderPolicy = new CacheDbSpringProperties.EntityPolicyProperties();
        orderPolicy.setHotEntityLimit(25_000);
        orderPolicy.setPageSize(250);

        CacheDbSpringProperties.HotPolicyProperties recent = new CacheDbSpringProperties.HotPolicyProperties();
        recent.setMode(EntityHotPolicyMode.TIME_WINDOW);
        recent.setTimeColumn("order_date");
        recent.setHotForSeconds(7_776_000L);
        CacheDbSpringProperties.HotPolicyProperties active = new CacheDbSpringProperties.HotPolicyProperties();
        active.setMode(EntityHotPolicyMode.STATE_WINDOW);
        active.setStateColumn("status");
        active.setStateValues(List.of("NEW", "PAID"));
        CacheDbSpringProperties.HotPolicyProperties composite = new CacheDbSpringProperties.HotPolicyProperties();
        composite.setMode(EntityHotPolicyMode.COMPOSITE);
        composite.setCompositeOperator(EntityHotPolicyCompositeOperator.ANY);
        composite.setChildren(List.of(recent, active));
        orderPolicy.setHotPolicy(composite);
        properties.getRegistration().getEntities().put("OrderEntity", orderPolicy);

        CachePolicyCatalog.Builder builder = CachePolicyCatalog.builder();
        CachePolicyCatalogFactory.addConfiguredPolicies(builder, properties.getRegistration(), CachePolicy.defaults());
        CachePolicy policy = builder.build().find("OrderEntity").orElseThrow();

        assertEquals(25_000, policy.hotEntityLimit());
        assertEquals(250, policy.pageSize());
        assertEquals(EntityHotPolicyMode.COMPOSITE, policy.hotPolicy().mode());
        assertEquals(EntityHotPolicyCompositeOperator.ANY, policy.hotPolicy().compositeOperator());
        assertEquals(2, policy.hotPolicy().children().size());
    }

    @Test
    void shouldRejectIncompleteDeclarativeTimeWindow() {
        CacheDbSpringProperties properties = new CacheDbSpringProperties();
        CacheDbSpringProperties.EntityPolicyProperties policy = new CacheDbSpringProperties.EntityPolicyProperties();
        CacheDbSpringProperties.HotPolicyProperties hotPolicy = new CacheDbSpringProperties.HotPolicyProperties();
        hotPolicy.setMode(EntityHotPolicyMode.TIME_WINDOW);
        policy.setHotPolicy(hotPolicy);
        properties.getRegistration().getEntities().put("OrderEntity", policy);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CachePolicyCatalogFactory.addConfiguredPolicies(
                        CachePolicyCatalog.builder(),
                        properties.getRegistration(),
                        CachePolicy.defaults()
                )
        );

        assertTrue(exception.getMessage().contains("TIME_WINDOW requires"));
    }

    @Test
    void shouldKeepLegacyRedisUriAliasWorking() {
        CacheDbSpringProperties properties = new CacheDbSpringProperties();

        properties.setRedisUri("redis://127.0.0.1:6380");

        assertEquals("redis://127.0.0.1:6380", properties.getRedisUri());
        assertEquals("redis://127.0.0.1:6380", properties.getRedis().getUri());
    }

    @Test
    void shouldAllowProfileSelectionForSimpleStarterSetup() {
        CacheDbSpringProperties properties = new CacheDbSpringProperties();

        properties.setProfile(CacheDbSpringProperties.Profile.PRODUCTION);

        assertEquals(CacheDbSpringProperties.Profile.PRODUCTION, properties.getProfile());
    }

    @Test
    void shouldBuildMssqlWriteBehindFactoryWhenSqlProviderIsSelected() {
        CacheDbSpringProperties properties = new CacheDbSpringProperties();
        properties.getSql().setProvider(CacheDbSpringProperties.SqlProvider.MSSQL);
        properties.getSql().getMssql().setLockTimeoutMillis(250);
        properties.getSql().getMssql().setQueryTimeoutSeconds(3);
        properties.getSql().getMssql().setRestoreLockTimeoutAfterTransaction(false);

        CacheDatabaseConfig config = new CacheDatabaseSpringBootAutoConfiguration().cacheDatabaseConfig(
                properties,
                new DefaultListableBeanFactory().getBeanProvider(CacheDatabaseConfigCustomizer.class)
        );

        assertNotNull(config.writeBehindFlusherFactory());
        Object flusher = config.writeBehindFlusherFactory().create(
                null,
                null,
                WriteBehindConfig.defaults(),
                StoragePerformanceCollector.noop()
        );
        assertInstanceOf(MssqlWriteBehindFlusher.class, flusher);
    }

    @Test
    void shouldFailFastWhenCustomSqlProviderHasNoFactoryOverride() {
        CacheDbSpringProperties properties = new CacheDbSpringProperties();
        properties.getSql().setProvider(CacheDbSpringProperties.SqlProvider.CUSTOM);

        CacheDatabaseConfig config = new CacheDatabaseSpringBootAutoConfiguration().cacheDatabaseConfig(
                properties,
                new DefaultListableBeanFactory().getBeanProvider(CacheDatabaseConfigCustomizer.class)
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> config.writeBehindFlusherFactory().create(
                null,
                null,
                WriteBehindConfig.defaults(),
                StoragePerformanceCollector.noop()
        ));
        assertTrue(exception.getMessage().contains("cachedb.sql.provider=custom requires"));
    }
}
