package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.CacheRuntimeRole;
import com.reactor.cachedb.redis.JedisPooledOperations;
import java.net.URI;
import java.lang.management.ManagementFactory;
import javax.management.*;
import java.time.Duration;
import java.util.*;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.util.JedisURIHelper;

/** Public SDK getters/constructors only. No reflective access to client, socket or pool internals. */
class BootstrapUriRedisClientFactoryTest {
    @Test void factoryChangesOnlyPoolFairnessAndDoesNotOpenConnections() {
        String uri = "redis://127.0.0.1:1/7?protocol=3";
        try (var original = new JedisPooled(uri); var fair = BootstrapUriRedisClientFactory.create(uri)) {
            assertFalse(original.getPool().getFairness());
            assertTrue(fair.getPool().getFairness());
            assertSamePoolSettings(original.getPool(), fair.getPool());
            assertEquals(0, original.getPool().getCreatedCount());
            assertEquals(0, fair.getPool().getCreatedCount());
            assertEquals(ConnectionPool.class, fair.getPool().getClass());
        }
    }

    @Test void genericDefaultsAreNotJedisConnectionPoolConfigTuning() {
        try (var fair = BootstrapUriRedisClientFactory.create("redis://127.0.0.1:1")) {
            var pool = fair.getPool();
            assertEquals(8, pool.getMaxTotal()); assertEquals(8, pool.getMaxIdle()); assertEquals(0, pool.getMinIdle());
            assertEquals(Duration.ofMillis(-1), pool.getMaxWaitDuration());
            assertTrue(pool.getLifo()); assertTrue(pool.getBlockWhenExhausted());
            assertFalse(pool.getTestWhileIdle());
            assertEquals(Duration.ofMillis(-1), pool.getDurationBetweenEvictionRuns());
            assertEquals(Duration.ofMinutes(30), pool.getMinEvictableIdleDuration());
            assertEquals(3, pool.getNumTestsPerEvictionRun());
            var tuned = new ConnectionPoolConfig();
            assertTrue(tuned.getTestWhileIdle());
            assertEquals(Duration.ofSeconds(30), tuned.getDurationBetweenEvictionRuns());
            assertEquals(Duration.ofSeconds(60), tuned.getMinEvictableIdleDuration());
            assertEquals(-1, tuned.getNumTestsPerEvictionRun());
        }
    }

    @Test void plainUriRetainsAllUnspecifiedClientDefaults() {
        var actual = BootstrapUriRedisClientFactory.clientConfig(URI.create("redis://localhost:6379"));
        assertClientDefaults(actual);
        assertNull(actual.getUser()); assertNull(actual.getPassword()); assertNull(actual.getRedisProtocol());
        assertEquals(0, actual.getDatabase()); assertFalse(actual.isSsl());
    }

    @Test void tlsAclEncodedCredentialsDatabaseAndProtocolArePreserved() {
        URI uri = URI.create("rediss://alice%2Bops:p%3Aa%40ss@cache.example:6380/11?protocol=3");
        var actual = BootstrapUriRedisClientFactory.clientConfig(uri);
        assertClientDefaults(actual);
        assertEquals("alice+ops", actual.getUser()); assertEquals("p:a@ss", actual.getPassword());
        assertEquals(11, actual.getDatabase()); assertEquals(RedisProtocol.RESP3, actual.getRedisProtocol());
        assertTrue(actual.isSsl());
        var passwordOnly = BootstrapUriRedisClientFactory.clientConfig(URI.create("redis://:secret@cache.example:6379/2?protocol=2"));
        assertNull(passwordOnly.getUser()); assertEquals("secret", passwordOnly.getPassword());
        assertEquals(2, passwordOnly.getDatabase()); assertEquals(RedisProtocol.RESP2, passwordOnly.getRedisProtocol());
        assertFalse(passwordOnly.isSsl());
        // The SDK helper is case-sensitive; fairness must not silently change scheme handling.
        assertFalse(BootstrapUriRedisClientFactory.clientConfig(URI.create("REDISS://localhost:6379")).isSsl());
    }

    @Test void queryProtocolRulesAndMalformedUriErrorsMatchTheOriginalConstructor() {
        assertNull(BootstrapUriRedisClientFactory.clientConfig(URI.create("redis://localhost:6379/?ignored=3")).getRedisProtocol());
        assertEquals(RedisProtocol.RESP2, BootstrapUriRedisClientFactory.clientConfig(
                URI.create("redis://localhost:6379/?protocol=2&protocol=3")).getRedisProtocol());
        assertEquals(RedisProtocol.RESP3, BootstrapUriRedisClientFactory.clientConfig(
                URI.create("redis://localhost:6379/?protocol=%33")).getRedisProtocol());
        for (String invalid : List.of("redis://localhost:6379/?protocol=9",
                "redis://localhost:6379/not-a-db", "redis://password-without-colon@localhost:6379")) {
            var oldFailure = assertThrows(RuntimeException.class, () -> new JedisPooled(invalid));
            var newFailure = assertThrows(RuntimeException.class, () -> BootstrapUriRedisClientFactory.create(invalid));
            assertEquals(oldFailure.getClass(), newFailure.getClass());
        }
    }

    @Test @Timeout(5) void absentPortRetainsSdkMinusOneInsteadOfInventingADefaultPort() {
        URI missing = URI.create("redis://127.0.0.1");
        assertEquals(-1, JedisURIHelper.getHostAndPort(missing).getPort());
        assertEquals(6379, JedisURIHelper.getHostAndPort(URI.create("redis://127.0.0.1:6379")).getPort());
        try (var original = new JedisPooled(missing.toString());
                var fair = BootstrapUriRedisClientFactory.create(missing.toString())) {
            // Port -1 is rejected before connect; this does not require a running Redis on6379.
            var oldFailure = assertThrows(JedisConnectionException.class, original::ping);
            var newFailure = assertThrows(JedisConnectionException.class, fair::ping);
            assertTrue(hasPortArgumentFailure(oldFailure));
            assertTrue(hasPortArgumentFailure(newFailure));
            assertEquals(0, fair.getPool().getNumActive());
        }
    }

    @Test @Timeout(10) void bootstrapOwnsTwoFairUriClientsWithoutChangingPoolDefaults() {
        var database = CacheDatabaseBootstrap.connect("redis://127.0.0.1:1/1?protocol=2",
                "rediss://127.0.0.1:1/2?protocol=3", source()).config(clientOnly()).build();
        JedisPooled foreground = ((JedisPooledOperations) database.redisOperations()).pooledClient();
        JedisPooled background = ((JedisPooledOperations) database.backgroundRedisOperations()).pooledClient();
        try (var original = new JedisPooled("redis://127.0.0.1:1")) {
            assertNotSame(foreground, background);
            assertTrue(foreground.getPool().getFairness()); assertTrue(background.getPool().getFairness());
            assertSamePoolSettings(original.getPool(), foreground.getPool());
            assertSamePoolSettings(original.getPool(), background.getPool());
            assertEquals(0, foreground.getPool().getCreatedCount());
            assertEquals(0, background.getPool().getCreatedCount());
        } finally { database.close(); }
        assertTrue(foreground.getPool().isClosed()); assertTrue(background.getPool().isClosed());
    }

    @Test @Timeout(10) void invalidBackgroundUriClosesAlreadyCreatedForegroundPool() throws Exception {
        var server = ManagementFactory.getPlatformMBeanServer();
        Thread caller = Thread.currentThread();
        var registered = new HashSet<ObjectName>();
        var unregistered = new HashSet<ObjectName>();
        NotificationListener listener = (notification, handback) -> {
            if (Thread.currentThread() != caller || !(notification instanceof MBeanServerNotification event)) return;
            ObjectName name = event.getMBeanName();
            if (!"org.apache.commons.pool2".equals(name.getDomain())
                    || !"GenericObjectPool".equals(name.getKeyProperty("type"))) return;
            if (MBeanServerNotification.REGISTRATION_NOTIFICATION.equals(event.getType())) registered.add(name);
            if (MBeanServerNotification.UNREGISTRATION_NOTIFICATION.equals(event.getType())) unregistered.add(name);
        };
        // Public synchronous JMX notifications isolate this caller's pools, even with parallel tests.
        server.addNotificationListener(MBeanServerDelegate.DELEGATE_NAME, listener, null, null);
        try {
            assertThrows(IllegalArgumentException.class, () -> CacheDatabaseBootstrap.connect(
                    "redis://127.0.0.1:1", "redis://127.0.0.1:1/?protocol=9", source()).config(clientOnly()).build());
            assertEquals(1, registered.size(), "Positive control: foreground pool really existed before background URI failed");
            assertEquals(registered, unregistered, "Owned foreground must close when background creation fails");
            for (ObjectName name : registered) assertFalse(server.isRegistered(name));
        } finally {
            server.removeNotificationListener(MBeanServerDelegate.DELEGATE_NAME, listener);
        }
    }

    @Test @Timeout(10) void borrowedSingleClientRemainsUnfairUnmodifiedAndOpen() {
        try (var client = borrowed(3)) {
            var pool = client.getPool();
            try (var database = CacheDatabaseBootstrap.using(client, source()).config(clientOnly()).build()) {
                assertSame(client, ((JedisPooledOperations) database.redisOperations()).pooledClient());
                assertSame(database.redisOperations(), database.backgroundRedisOperations());
                assertSame(pool, client.getPool()); assertBorrowed(client, 3);
            }
            assertFalse(pool.isClosed()); assertBorrowed(client, 3);
        }
    }

    @Test @Timeout(10) void borrowedDualClientsRetainBothIndependentPoolsAndOwnership() {
        try (var foreground = borrowed(3); var background = borrowed(5)) {
            try (var database = CacheDatabaseBootstrap.using(foreground, background, source()).config(clientOnly()).build()) {
                assertSame(foreground, ((JedisPooledOperations) database.redisOperations()).pooledClient());
                assertSame(background, ((JedisPooledOperations) database.backgroundRedisOperations()).pooledClient());
                assertBorrowed(foreground, 3); assertBorrowed(background, 5);
            }
            assertFalse(foreground.getPool().isClosed()); assertFalse(background.getPool().isClosed());
            assertBorrowed(foreground, 3); assertBorrowed(background, 5);
        }
    }

    private static JedisPooled borrowed(int max) {
        var pool = new GenericObjectPoolConfig<Connection>();
        pool.setFairness(false); pool.setLifo(false); pool.setMaxTotal(max); pool.setMaxIdle(2);
        pool.setMaxWait(Duration.ofMillis(73));
        var client = DefaultJedisClientConfig.builder().connectionTimeoutMillis(321)
                .socketTimeoutMillis(654).blockingSocketTimeoutMillis(987).build();
        return new JedisPooled(new HostAndPort("127.0.0.1", 1), client, pool);
    }
    private static void assertBorrowed(JedisPooled client, int max) {
        var pool = client.getPool();
        assertFalse(pool.getFairness()); assertFalse(pool.getLifo()); assertEquals(max, pool.getMaxTotal());
        assertEquals(2, pool.getMaxIdle()); assertEquals(Duration.ofMillis(73), pool.getMaxWaitDuration());
        assertEquals(0, pool.getCreatedCount()); assertEquals(0, pool.getBorrowedCount());
    }
    private static CacheDatabaseConfig clientOnly() {
        return CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY).build();
    }
    private static JdbcDataSource source() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:bootstrap-uri-" + UUID.randomUUID()); return source;
    }
    private static void assertClientDefaults(DefaultJedisClientConfig actual) {
        var expected = DefaultJedisClientConfig.builder().build();
        assertEquals(2000, actual.getConnectionTimeoutMillis()); assertEquals(2000, actual.getSocketTimeoutMillis());
        assertEquals(0, actual.getBlockingSocketTimeoutMillis());
        assertEquals(expected.getConnectionTimeoutMillis(), actual.getConnectionTimeoutMillis());
        assertEquals(expected.getSocketTimeoutMillis(), actual.getSocketTimeoutMillis());
        assertEquals(expected.getBlockingSocketTimeoutMillis(), actual.getBlockingSocketTimeoutMillis());
        assertEquals(expected.getClientName(), actual.getClientName());
        assertSame(expected.getSslSocketFactory(), actual.getSslSocketFactory());
        assertSame(expected.getSslParameters(), actual.getSslParameters());
        assertSame(expected.getHostnameVerifier(), actual.getHostnameVerifier());
        assertSame(expected.getHostAndPortMapper(), actual.getHostAndPortMapper());
        assertSame(expected.getClientSetInfoConfig(), actual.getClientSetInfoConfig());
        assertEquals(expected.isReadOnlyForRedisClusterReplicas(), actual.isReadOnlyForRedisClusterReplicas());
        assertEquals(actual.getUser(), actual.getCredentialsProvider().get().getUser());
        String password = actual.getPassword();
        assertArrayEquals(password == null ? null : password.toCharArray(),
                actual.getCredentialsProvider().get().getPassword());
    }
    private static void assertSamePoolSettings(GenericObjectPool<Connection> expected, GenericObjectPool<Connection> actual) {
        assertEquals(expected.getMaxTotal(), actual.getMaxTotal());
        assertEquals(expected.getMaxIdle(), actual.getMaxIdle()); assertEquals(expected.getMinIdle(), actual.getMinIdle());
        assertEquals(expected.getLifo(), actual.getLifo()); assertEquals(expected.getBlockWhenExhausted(), actual.getBlockWhenExhausted());
        assertEquals(expected.getMaxWaitDuration(), actual.getMaxWaitDuration());
        assertEquals(expected.getTestOnCreate(), actual.getTestOnCreate()); assertEquals(expected.getTestOnBorrow(), actual.getTestOnBorrow());
        assertEquals(expected.getTestOnReturn(), actual.getTestOnReturn()); assertEquals(expected.getTestWhileIdle(), actual.getTestWhileIdle());
        assertEquals(expected.getDurationBetweenEvictionRuns(), actual.getDurationBetweenEvictionRuns());
        assertEquals(expected.getMinEvictableIdleDuration(), actual.getMinEvictableIdleDuration());
        assertEquals(expected.getSoftMinEvictableIdleDuration(), actual.getSoftMinEvictableIdleDuration());
        assertEquals(expected.getNumTestsPerEvictionRun(), actual.getNumTestsPerEvictionRun());
        assertEquals(expected.getEvictorShutdownTimeoutDuration(), actual.getEvictorShutdownTimeoutDuration());
        assertEquals(expected.getEvictionPolicyClassName(), actual.getEvictionPolicyClassName());
        assertEquals(expected.getFactory().getClass(), actual.getFactory().getClass());
        assertEquals(expected.isAbandonedConfig(), actual.isAbandonedConfig());
        assertEquals(expected.getLogAbandoned(), actual.getLogAbandoned());
        assertEquals(expected.getRemoveAbandonedOnBorrow(), actual.getRemoveAbandonedOnBorrow());
        assertEquals(expected.getRemoveAbandonedOnMaintenance(), actual.getRemoveAbandonedOnMaintenance());
        assertEquals(expected.getRemoveAbandonedTimeoutDuration(), actual.getRemoveAbandonedTimeoutDuration());
        assertEquals(expected.getMessageStatistics(), actual.getMessageStatistics());
        assertSame(expected.getSwallowedExceptionListener(), actual.getSwallowedExceptionListener());
        assertEquals(expected.getJmxName() == null, actual.getJmxName() == null);
        if (expected.getJmxName() != null) {
            assertEquals(expected.getJmxName().getDomain(), actual.getJmxName().getDomain());
            assertEquals(expected.getJmxName().getKeyProperty("type"), actual.getJmxName().getKeyProperty("type"));
        }
    }
    private static boolean hasPortArgumentFailure(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>(); pending.add(failure);
        for (int remaining = 64; remaining > 0 && !pending.isEmpty(); remaining--) {
            Throwable next = pending.removeFirst();
            if (!seen.add(next)) continue;
            if (next instanceof IllegalArgumentException && next.getMessage() != null && next.getMessage().contains("-1")) return true;
            if (next.getCause() != null && pending.size() < 64) pending.addLast(next.getCause());
            for (Throwable child : next.getSuppressed()) {
                if (pending.size() >= 64) break;
                pending.addLast(child);
            }
        }
        return false;
    }
}
