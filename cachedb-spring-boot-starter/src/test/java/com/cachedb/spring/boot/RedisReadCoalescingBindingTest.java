package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RedisReadCoalescingBindingTest {
    @Test void relaxedNestedBindingReachesFactoryAndConfigRoundTrip() {
        var source = new MapConfigurationPropertySource(Map.of(
                "cachedb.redis.read-coalescing.enabled", "false",
                "cachedb.redis.read-coalescing.max-batch-keys", "37",
                "cachedb.redis.read-coalescing.max-queued-requests", "101",
                "cachedb.redis.read-coalescing.max-in-flight-batches", "3",
                "cachedb.redis.read-coalescing.max-queued-bytes", "100000",
                "cachedb.redis.read-coalescing.max-reply-bytes", "200000",
                "cachedb.redis.read-coalescing.max-live-reply-bytes", "67108864",
                "cachedb.redis.read-coalescing.timeout-millis", "3456",
                "cachedb.redis.read-coalescing.max-async-direct", "7"));
        var properties = new Binder(source).bind("cachedb", Bindable.of(CacheDbSpringProperties.class)).get();
        properties.afterPropertiesSet();
        var options = new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(properties,
                new DefaultListableBeanFactory().getBeanProvider(CacheRuntimeOptionsCustomizer.class));
        var expected = new RedisReadCoalescingConfig(false, 37, 101, 3, 100000, 200000, 3456, 7, 64L * 1024 * 1024);
        assertEquals(64L * 1024 * 1024, properties.getRedis().getReadCoalescing().getMaxLiveReplyBytes());
        assertEquals(expected, options.redisReadCoalescing());
        assertEquals(expected, options.toBuilder().build().redisReadCoalescing());
    }
    @Test void liveReplyBudgetCannotBeSmallerThanPerBatchReplyBudgetEvenWhenDisabled() {
        for (boolean enabled : new boolean[] { false, true }) {
            var source = new MapConfigurationPropertySource(Map.of(
                    "cachedb.redis.read-coalescing.enabled", Boolean.toString(enabled),
                    "cachedb.redis.read-coalescing.max-reply-bytes", "2048",
                    "cachedb.redis.read-coalescing.max-live-reply-bytes", "1024"));
            var properties = new Binder(source).bind("cachedb", Bindable.of(CacheDbSpringProperties.class)).get();
            var failure = assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);
            assertTrue(failure.getMessage().contains("cachedb.redis.read-coalescing"));
            assertThrows(IllegalArgumentException.class, () ->
                    new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(properties));
        }
    }
    @Test void liveReplyBudgetMayEqualOneBatchAndRoundTripsThroughRuntimeOptions() {
        var properties = new CacheDbSpringProperties();
        var reads = properties.getRedis().getReadCoalescing();
        reads.setMaxReplyBytes(1024); reads.setMaxLiveReplyBytes(1024);
        properties.afterPropertiesSet();
        var options = new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(properties);
        assertEquals(1024, options.redisReadCoalescing().maxLiveReplyBytes());
        assertEquals(options.redisReadCoalescing(), options.toBuilder().build().redisReadCoalescing());
        assertEquals(21, com.reactor.cachedb.core.config.CacheDatabaseConfig.class.getRecordComponents().length);
    }
    @Test void customizerWinsAfterPropertyBinding() {
        var properties = new CacheDbSpringProperties();
        var beans = new DefaultListableBeanFactory();
        var selected = RedisReadCoalescingConfig.builder().enabled(false).maxBatchKeys(29).build();
        beans.registerSingleton("readTuning", (CacheRuntimeOptionsCustomizer) (builder, ignored) ->
                builder.redisReadCoalescing(selected));
        var options = new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(properties,
                beans.getBeanProvider(CacheRuntimeOptionsCustomizer.class));
        assertSame(selected, options.redisReadCoalescing());
    }
    @Test void invalidPropertiesFailEvenWhenBatchingIsDisabled() {
        List<Consumer<CacheDbSpringProperties.ReadCoalescingProperties>> invalid = List.of(
                p -> p.setMaxBatchKeys(513), p -> p.setMaxQueuedRequests(4097),
                p -> p.setMaxInFlightBatches(RedisReadCoalescingConfig.HARD_IN_FLIGHT + 1),
                p -> p.setMaxQueuedBytes(16777217), p -> p.setMaxReplyBytes(0),
                p -> p.setTimeoutMillis(0), p -> p.setMaxAsyncDirect(1025),
                p -> p.setMaxLiveReplyBytes(0),
                p -> p.setMaxLiveReplyBytes(RedisReadCoalescingConfig.HARD_LIVE_REPLY_BYTES + 1));
        for (var mutation : invalid) {
            var properties = new CacheDbSpringProperties();
            properties.getRedis().getReadCoalescing().setEnabled(false);
            mutation.accept(properties.getRedis().getReadCoalescing());
            var failure = assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);
            assertTrue(failure.getMessage().contains("cachedb.redis.read-coalescing"));
        }
    }
    @Test void defaultsMatchTransportWithoutASecondHardCodedPolicy() {
        var properties = new CacheDbSpringProperties();
        properties.afterPropertiesSet();
        assertEquals(RedisReadCoalescingConfig.defaults(), properties.getRedis().getReadCoalescing().toConfig());
    }

    @Test void runtimeAndBaseCustomizersRemainIndependent() {
        var properties = new CacheDbSpringProperties();
        properties.getSql().setProvider(CacheDbSpringProperties.SqlProvider.POSTGRES);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("baseTuning", (CacheDatabaseConfigCustomizer) (builder, ignored) ->
                builder.mutations(new com.reactor.cachedb.core.config.MutationConfig(7, false)));
        beans.registerSingleton("runtimeTuning", (CacheRuntimeOptionsCustomizer) (builder, ignored) ->
                builder.redisReadCoalescing(RedisReadCoalescingConfig.builder().enabled(false).build()));
        var factory = new CacheDatabaseSpringBootAutoConfiguration();
        var base = factory.cacheDatabaseConfig(properties, beans.getBeanProvider(CacheDatabaseConfigCustomizer.class));
        var options = factory.cacheRuntimeOptions(properties, beans.getBeanProvider(CacheRuntimeOptionsCustomizer.class));
        assertEquals(7, base.mutations().maxPatchAttempts()); assertFalse(base.mutations().coldPatchEnabled());
        assertFalse(options.redisReadCoalescing().enabled());
        assertEquals(21, base.getClass().getRecordComponents().length);
    }
    @Test void originalSpringFactorySignaturesRemainAndOnlyTheOptionsOverloadIsABean() throws Exception {
        var type = CacheDatabaseSpringBootAutoConfiguration.class;
        var provider = org.springframework.beans.factory.ObjectProvider.class;
        var dataSource = javax.sql.DataSource.class;
        var base = com.reactor.cachedb.core.config.CacheDatabaseConfig.class;
        var policies = com.reactor.cachedb.core.cache.CachePolicyCatalog.class;
        var props = CacheDbSpringProperties.class;
        assertNotNull(type.getMethod("cacheDatabase", provider, provider, provider, dataSource, base, props));
        var old = type.getMethod("cacheDatabase", provider, provider, provider, dataSource, base, policies, props);
        var selected = type.getMethod("cacheDatabase", provider, provider, provider, dataSource, base, policies, props,
                com.reactor.cachedb.core.config.CacheRuntimeOptions.class);
        assertNull(old.getAnnotation(org.springframework.context.annotation.Bean.class));
        assertNotNull(selected.getAnnotation(org.springframework.context.annotation.Bean.class));
        assertNotNull(type.getMethod("cacheDatabaseConfig", props, provider));
    }
}
