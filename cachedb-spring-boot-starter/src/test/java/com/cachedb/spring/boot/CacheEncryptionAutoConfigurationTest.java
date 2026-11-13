package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.CacheRuntimeOptions;
import com.reactor.cachedb.core.crypto.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class CacheEncryptionAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheEncryptionAutoConfiguration.class));

    private static CacheKeyProvider provider() {
        return new CacheKeyProvider() {
            public KeyMaterial current(String ring) { throw new AssertionError("No key lookup during options binding"); }
            public Optional<KeyMaterial> byId(String ring, String key) { throw new AssertionError(); }
            public String toString() { throw new AssertionError("Never stringify providers"); }
        };
    }

    @Test void optionalProviderUsesRuntimeOptionsAndLeavesBaseRecordShapeAlone() {
        var provider = provider();
        runner.withBean(CacheKeyProvider.class, () -> provider).run(context -> {
            var options = new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(
                    new CacheDbSpringProperties(), context.getBeanProvider(CacheRuntimeOptionsCustomizer.class));
            assertSame(provider, options.keyProvider());
            assertSame(provider, options.toBuilder().build().keyProvider());
            assertEquals(21, CacheDatabaseConfig.class.getRecordComponents().length);
            assertFalse(CacheRuntimeOptions.class.isRecord());
            assertNotNull(options.toString());
        });
    }

    @Test void absenceDoesNotInventKeysOrOverwriteExplicitOptions() {
        runner.run(context -> {
            var options = new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(
                    new CacheDbSpringProperties(), context.getBeanProvider(CacheRuntimeOptionsCustomizer.class));
            assertNull(options.keyProvider());
            var provider = provider();
            var builder = CacheRuntimeOptions.builder().keyProvider(provider);
            context.getBean(CacheRuntimeOptionsCustomizer.class).customize(builder, new CacheDbSpringProperties());
            assertSame(provider, builder.build().keyProvider());
        });
    }

    @Test void ambiguousProviderSelectionFailsClosedWithoutOriginalExceptionChain() {
        runner.withBean("first", CacheKeyProvider.class, CacheEncryptionAutoConfigurationTest::provider)
                .withBean("second", CacheKeyProvider.class, CacheEncryptionAutoConfigurationTest::provider)
                .run(context -> {
                    var failure = assertThrows(CacheEncryptionException.class,
                            () -> new CacheDatabaseSpringBootAutoConfiguration().cacheRuntimeOptions(
                                    new CacheDbSpringProperties(),
                                    context.getBeanProvider(CacheRuntimeOptionsCustomizer.class)));
                    assertNull(failure.getCause());
                    assertEquals(0, failure.getSuppressed().length);
                });
    }

    @Test void disabledAutoConfigurationDoesNotInstallTheProviderCustomizer() {
        runner.withPropertyValues("cachedb.enabled=false").run(context ->
                assertTrue(context.getBeansOfType(CacheRuntimeOptionsCustomizer.class).isEmpty()));
    }

    @Test void cryptoOptionsCopyPreservesCurrentNativeTrackingAndObservationConfiguration() {
        var keys = provider();
        var alternateKeys = provider();
        var reads = com.reactor.cachedb.core.config.RedisReadCoalescingConfig.builder()
                .maxBatchKeys(17).maxAsyncDirect(7).maxLiveReplyBytes(32L * 1024 * 1024).build();
        var tracking = com.reactor.cachedb.core.config.NearCacheInvalidationConfig.builder()
                .mode(com.reactor.cachedb.core.config.NearCacheInvalidationMode.SERVER_TRACKING)
                .publishLegacyInvalidations(false).maxMasters(2).maxNodes(3).build();
        java.util.concurrent.Executor borrowed = task -> { throw new AssertionError("Options binding cannot dispatch work"); };
        com.reactor.cachedb.core.observe.CacheObservationRecorder recorder =
                operation -> { throw new AssertionError("Options copy cannot start an observation"); };
        com.reactor.cachedb.core.observe.CacheTraceparentSupplier traceparent = () -> null;
        var options = CacheRuntimeOptions.builder().redisReadCoalescing(reads).asyncMaxConcurrent(11)
                .asyncExecutor(borrowed).nearCacheInvalidation(tracking).observationRecorder(recorder)
                .observationTraceparentSupplier(traceparent).keyProvider(keys).build();
        var changed = options.toBuilder().keyProvider(alternateKeys).build();
        assertSame(keys, options.keyProvider());
        assertSame(alternateKeys, changed.keyProvider());
        assertSame(reads, changed.redisReadCoalescing());
        assertEquals(17, changed.redisReadCoalescing().maxBatchKeys());
        assertEquals(7, changed.redisReadCoalescing().maxAsyncDirect());
        assertEquals(32L * 1024 * 1024, changed.redisReadCoalescing().maxLiveReplyBytes());
        assertEquals(11, changed.asyncMaxConcurrent());
        assertSame(borrowed, changed.asyncExecutor());
        assertSame(tracking, changed.nearCacheInvalidation());
        assertFalse(changed.nearCacheInvalidation().publishLegacyInvalidations());
        assertSame(recorder, changed.observationRecorder());
        assertSame(traceparent, changed.observationTraceparentSupplier());
        assertSame(keys, options.toBuilder().asyncMaxConcurrent(13).build().keyProvider());
    }
}
