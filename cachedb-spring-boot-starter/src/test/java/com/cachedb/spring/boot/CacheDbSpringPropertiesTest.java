package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.mssql.MssqlWriteBehindFlusher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

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
        assertTrue(properties.getRegistration().isEnabled());
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
