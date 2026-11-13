package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class NearCacheInvalidationBindingTest {
    private static CacheDbSpringProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("cachedb", Bindable.of(CacheDbSpringProperties.class)).get();
    }
    @Test void nativeNestedPropertiesReachConfigFactoryAndRoundTrip() {
        var p = bind(Map.ofEntries(Map.entry("cachedb.near-cache.invalidation-mode", "SERVER_TRACKING"),
                Map.entry("cachedb.near-cache.publish-legacy-invalidations", "false"),
                Map.entry("cachedb.near-cache.max-masters", "2"), Map.entry("cachedb.near-cache.max-nodes", "4"),
                Map.entry("cachedb.near-cache.max-namespaces", "12"), Map.entry("cachedb.near-cache.io-timeout-millis", "600"),
                Map.entry("cachedb.near-cache.heartbeat-millis", "100"), Map.entry("cachedb.near-cache.health-timeout-millis", "900"),
                Map.entry("cachedb.near-cache.max-attempts", "4"), Map.entry("cachedb.near-cache.initial-backoff-millis", "200"),
                Map.entry("cachedb.near-cache.max-backoff-millis", "1000"), Map.entry("cachedb.near-cache.retry-cooldown-millis", "2000")));
        p.afterPropertiesSet();
        var c = new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(p,
                new DefaultListableBeanFactory().getBeanProvider(CacheRuntimeOptionsCustomizer.class));
        assertEquals(new NearCacheInvalidationConfig(NearCacheInvalidationMode.SERVER_TRACKING, false,
                2, 4, 12, 600, 100, 900, 4, 200, 1000, 2000), c.nearCacheInvalidation());
        assertEquals(c.nearCacheInvalidation(), c.toBuilder().build().nearCacheInvalidation());
    }
    @Test void asymmetricFallbackOptOutFailsBeforeAnyConnectionIsMade() {
        for (String mode : new String[]{"AUTO", "PUBSUB"}) {
            var p = bind(Map.of("cachedb.near-cache.invalidation-mode", mode,
                    "cachedb.near-cache.publish-legacy-invalidations", "false"));
            var failure = assertThrows(IllegalArgumentException.class, p::afterPropertiesSet);
            assertTrue(failure.getMessage().contains("cachedb.near-cache"));
            assertTrue(failure.getMessage().contains("all-consumer"));
        }
    }
    @Test void boundedSettingsRejectInvalidValuesEvenForPubsub() {
        for (var pair : Map.of("max-masters", "17", "max-nodes", "65", "max-namespaces", "257",
                "max-attempts", "9", "health-timeout-millis", "10001", "io-timeout-millis", "0").entrySet()) {
            var p = bind(Map.of("cachedb.near-cache.invalidation-mode", "PUBSUB",
                    "cachedb.near-cache." + pair.getKey(), pair.getValue()));
            assertThrows(IllegalArgumentException.class, p::afterPropertiesSet);
        }
    }
    @Test void customizerStillRunsAfterProperties() {
        var p = new CacheDbSpringProperties(); p.afterPropertiesSet();
        var factory = new DefaultListableBeanFactory();
        var selected = NearCacheInvalidationConfig.builder().mode(NearCacheInvalidationMode.PUBSUB).build();
        factory.registerSingleton("invalidationTuning", (CacheRuntimeOptionsCustomizer) (builder, properties) ->
                builder.nearCacheInvalidation(selected));
        var c = new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(p,
                factory.getBeanProvider(CacheRuntimeOptionsCustomizer.class));
        assertSame(selected, c.nearCacheInvalidation());
    }
}
