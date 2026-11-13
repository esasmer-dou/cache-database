package com.reactor.cachedb.spring.boot.observe;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.CacheRuntimeOptions;
import com.reactor.cachedb.core.observe.CacheObservationRecorder;
import com.reactor.cachedb.core.observe.NoOpCacheObservationRecorder;
import com.reactor.cachedb.spring.boot.CacheDbSpringProperties;
import com.reactor.cachedb.spring.boot.CacheRuntimeOptionsCustomizer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CacheObservationRuntimeOptionsCustomizerTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheObservationAutoConfiguration.class));

    @Test void explicitNativeSupplierIsUsedWithoutInvokingItDuringConfiguration() {
        com.reactor.cachedb.core.observe.CacheTraceparentSupplier supplier =
                () -> { throw new AssertionError("Configuration must not capture a span"); };
        context.withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withBean(com.reactor.cachedb.core.observe.CacheTraceparentSupplier.class, () -> supplier).run(application -> {
                    assertNull(application.getStartupFailure());
                    var builder = CacheRuntimeOptions.builder();
                    application.getBean("cacheObservationRuntimeOptionsCustomizer", CacheRuntimeOptionsCustomizer.class)
                            .customize(builder, new CacheDbSpringProperties());
                    assertSame(supplier, builder.build().observationTraceparentSupplier());
                });
    }

    @Test void optionalRegistryRecorderIsCopiedIntoOrdinaryRuntimeOptions() {
        context.withBean(ObservationRegistry.class, ObservationRegistry::create).run(application -> {
            assertNull(application.getStartupFailure());
            var recorder = application.getBean(CacheObservationRecorder.class);
            var builder = CacheRuntimeOptions.builder();
            application.getBean("cacheObservationRuntimeOptionsCustomizer", CacheRuntimeOptionsCustomizer.class)
                    .customize(builder, new CacheDbSpringProperties());
            assertSame(recorder, builder.build().observationRecorder());
        });
    }

    @Test void userRecorderIsPreservedWithoutStartingAnObservation() {
        CacheObservationRecorder recorder = operation -> { throw new AssertionError("Configuration must not start observations"); };
        context.withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withBean(CacheObservationRecorder.class, () -> recorder).run(application -> {
                    assertNull(application.getStartupFailure());
                    var builder = CacheRuntimeOptions.builder();
                    application.getBean("cacheObservationRuntimeOptionsCustomizer", CacheRuntimeOptionsCustomizer.class)
                            .customize(builder, new CacheDbSpringProperties());
                    assertSame(recorder, builder.build().observationRecorder());
                });
    }

    @Test void absentRegistryOrExplicitDisableKeepsNoOpDefaults() {
        context.run(application -> {
            assertNull(application.getStartupFailure());
            assertFalse(application.containsBean("cacheObservationRuntimeOptionsCustomizer"));
            assertSame(NoOpCacheObservationRecorder.INSTANCE, CacheRuntimeOptions.defaults().observationRecorder());
        });
        context.withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withPropertyValues("cachedb.observation.enabled=false").run(application -> {
                    assertNull(application.getStartupFailure());
                    assertFalse(application.containsBean("cacheObservationRuntimeOptionsCustomizer"));
                });
    }
}
