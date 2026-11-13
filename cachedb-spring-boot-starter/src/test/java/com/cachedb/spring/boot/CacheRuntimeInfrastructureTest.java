package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.CachePolicyCatalog;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import redis.clients.jedis.JedisPooled;

/** Wiring-only tests: constructor pools remain lazy; command probes never open sockets. */
class CacheRuntimeInfrastructureTest {
    private static CacheDatabaseConfig base(boolean loadFunctions) {
        return CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                .redisFunctions(RedisFunctionsConfig.builder().enabled(loadFunctions).autoLoadLibrary(loadFunctions)
                        .sourceOverride("#!lua name=__LIBRARY_NAME__\n").build()).build();
    }
    private static JdbcDataSource dataSource() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:t1-runtime-wiring"); return source;
    }
    private static CacheDbSpringProperties properties() {
        var properties = new CacheDbSpringProperties();
        properties.getRegistration().setEnabled(false);
        properties.getSql().setProvider(CacheDbSpringProperties.SqlProvider.CUSTOM);
        return properties;
    }
    private static void assertClosed(CacheDatabase database) {
        var failure = assertThrows(CompletionException.class, () ->
                database.redisReadRuntime().readAsync("closed-entity", "closed-tombstone", false).join());
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    }
    @Test void failedFunctionStartupClosesTheNewRuntimeWithoutClosingBorrowedClients() {
        var primary = new IllegalStateException("function-start-failure");
        try (var client = new ProbeClient(primary);
                var database = new CacheDatabase(client, dataSource(), base(true), CacheRuntimeOptions.defaults())) {
            var failure = assertThrows(IllegalStateException.class, () ->
                    new CacheDatabaseSpringBootAutoConfiguration().initializeCacheDatabase(
                            database, database.config(), CachePolicyCatalog.empty(), properties()));
            assertSame(primary, failure);
            assertEquals(1, client.gets.get());
            assertClosed(database);
            assertEquals(0, client.closes.get());
            assertFalse(client.getPool().isClosed());
        }
    }
    @Test void failedGeneratedRegistrationAlsoClosesTheNewRuntimeBeforeBeanPublication() {
        try (var client = new ProbeClient(new IllegalStateException("unexpected-command"));
                var database = new CacheDatabase(client, dataSource(), base(false), CacheRuntimeOptions.defaults())) {
            var properties = properties();
            properties.getRegistration().setEnabled(true);
            properties.getRegistration().setSource(CacheDbSpringProperties.RegistrationSource.JDBC);
            properties.getRegistration().setFailOnUnknownEntity(true);
            var catalog = CachePolicyCatalog.builder()
                    .policy("NoGeneratedEntityForT1WiringReview", CachePolicy.defaults()).build();
            var failure = assertThrows(IllegalStateException.class, () ->
                    new CacheDatabaseSpringBootAutoConfiguration().initializeCacheDatabase(
                            database, database.config(), catalog, properties));
            assertTrue(failure.getMessage().contains("NoGeneratedEntityForT1WiringReview"));
            assertEquals(0, client.gets.get());
            assertClosed(database);
            assertEquals(0, client.closes.get());
        }
    }
    @Test void startupUnwindPreservesRuntimeErrorsAndSuppressedCleanupFailures() {
        for (Throwable primary : new Throwable[] { new IllegalStateException("startup"), new AssertionError("startup-error") }) {
            var database = mock(CacheDatabase.class);
            var cleanup = new IllegalStateException("cleanup");
            doThrow(primary).when(database).start();
            doThrow(cleanup).when(database).close();
            var failure = assertThrows(Throwable.class, () ->
                    new CacheDatabaseSpringBootAutoConfiguration().initializeCacheDatabase(
                            database, base(false), CachePolicyCatalog.empty(), properties()));
            assertSame(primary, failure);
            assertArrayEquals(new Throwable[] { cleanup }, failure.getSuppressed());
            verify(database).close();
        }
    }
    private static ApplicationContextRunner context(ProbeClient client) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CacheDatabaseSpringBootAutoConfiguration.class))
                .withBean("cacheDbJedisPooled", JedisPooled.class, () -> client)
                .withBean(javax.sql.DataSource.class, CacheRuntimeInfrastructureTest::dataSource)
                .withBean(CacheDatabaseConfig.class, () -> base(false))
                .withPropertyValues("cachedb.runtime-role=CLIENT_ONLY", "cachedb.sql.provider=CUSTOM",
                        "cachedb.registration.enabled=false", "cachedb.redis.background.enabled=false",
                        "cachedb.scheduled-warm.enabled=false", "cachedb.jobs.enabled=false", "cachedb.admin.enabled=false",
                        "cachedb.redis.read-coalescing.max-batch-keys=37",
                        "cachedb.redis.read-coalescing.max-live-reply-bytes=67108864");
    }
    @Test void realSpringBeanUsesBoundOptionsAndOrderedCustomizers() {
        var client = new ProbeClient(new IllegalStateException("unexpected-command"));
        var order = new java.util.ArrayList<Integer>();
        context(client)
                .withBean("earlyRuntime", CacheRuntimeOptionsCustomizer.class, () -> new OrderedCustomizer(1, 31, order))
                .withBean("lateRuntime", CacheRuntimeOptionsCustomizer.class, () -> new OrderedCustomizer(2, 43, order))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var options = context.getBean(CacheRuntimeOptions.class);
                    var database = context.getBean(CacheDatabase.class);
                    assertSame(options, database.runtimeOptions());
                    assertEquals(List.of(1, 2), order);
                    assertEquals(43, options.redisReadCoalescing().maxBatchKeys());
                    assertEquals(64L * 1024 * 1024, options.redisReadCoalescing().maxLiveReplyBytes());
                    assertEquals(0, client.gets.get()); assertEquals(0, client.closes.get());
                });
        assertEquals(1, client.closes.get(), "Only Spring owns the supplied client lifecycle");
    }
    @Test void explicitOptionsBeanBacksOffAutomaticCustomizationAndReachesDatabaseUnchanged() {
        var client = new ProbeClient(new IllegalStateException("unexpected-command"));
        var selected = CacheRuntimeOptions.builder().redisReadCoalescing(
                RedisReadCoalescingConfig.builder().enabled(false).maxBatchKeys(19).build()).build();
        context(client)
                .withBean(CacheRuntimeOptions.class, () -> selected)
                .withBean(CacheRuntimeOptionsCustomizer.class, () -> (builder, properties) -> {
                    throw new AssertionError("Automatic options factory must back off");
                })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(selected, context.getBean(CacheDatabase.class).runtimeOptions());
                    assertEquals(0, client.gets.get());
                });
        assertEquals(1, client.closes.get());
    }
    @Test void bothLegacyFactorySignaturesRemainCallableAndBorrowThePool() {
        try (var client = new ProbeClient(new IllegalStateException("unexpected-command"))) {
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("borrowed", client);
            var provider = beans.getBeanProvider(JedisPooled.class);
            var properties = properties();
            properties.getRedis().getReadCoalescing().setEnabled(false);
            properties.getRedis().getReadCoalescing().setMaxLiveReplyBytes(64L * 1024 * 1024);
            var factory = new CacheDatabaseSpringBootAutoConfiguration();
            try (var six = factory.cacheDatabase(provider, provider, provider, dataSource(), base(false), properties);
                    var seven = factory.cacheDatabase(provider, provider, provider, dataSource(), base(false),
                            CachePolicyCatalog.empty(), properties)) {
                assertEquals(properties.getRedis().getReadCoalescing().toConfig(), six.runtimeOptions().redisReadCoalescing());
                assertEquals(six.runtimeOptions().redisReadCoalescing(), seven.runtimeOptions().redisReadCoalescing());
            }
            assertEquals(0, client.closes.get()); assertEquals(0, client.gets.get());
        }
    }
    @Test void explicitFactoryOverloadUsesSuppliedOptionsInsteadOfRebuildingFromProperties() {
        try (var client = new ProbeClient(new IllegalStateException("unexpected-command"))) {
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("borrowed", client);
            var provider = beans.getBeanProvider(JedisPooled.class);
            var selected = CacheRuntimeOptions.builder().redisReadCoalescing(
                    RedisReadCoalescingConfig.builder().enabled(false).maxBatchKeys(17).build()).build();
            try (var database = new CacheDatabaseSpringBootAutoConfiguration().cacheDatabase(
                    provider, provider, provider, dataSource(), base(false), CachePolicyCatalog.empty(), properties(), selected)) {
                assertSame(selected, database.runtimeOptions());
                assertEquals(0, client.gets.get());
            }
            assertEquals(0, client.closes.get());
        }
    }
    private static final class OrderedCustomizer implements CacheRuntimeOptionsCustomizer, org.springframework.core.Ordered {
        final int order, keys;
        final List<Integer> observed;
        OrderedCustomizer(int order, int keys, List<Integer> observed) { this.order = order; this.keys = keys; this.observed = observed; }
        public int getOrder() { return order; }
        public void customize(CacheRuntimeOptions.Builder builder, CacheDbSpringProperties properties) {
            observed.add(order);
            var current = properties.getRedis().getReadCoalescing().toConfig();
            builder.redisReadCoalescing(new RedisReadCoalescingConfig(current.enabled(), keys,
                    current.maxQueuedRequests(), current.maxInFlightBatches(), current.maxQueuedBytes(),
                    current.maxReplyBytes(), current.timeoutMillis(), current.maxAsyncDirect(), current.maxLiveReplyBytes()));
        }
    }
    private static final class ProbeClient extends JedisPooled {
        final RuntimeException failure;
        final AtomicInteger gets = new AtomicInteger(), closes = new AtomicInteger();
        ProbeClient(RuntimeException failure) { super("redis://127.0.0.1:1"); this.failure = failure; }
        @Override public String get(String key) { gets.incrementAndGet(); throw failure; }
        @Override public void close() { closes.incrementAndGet(); super.close(); }
    }
}
