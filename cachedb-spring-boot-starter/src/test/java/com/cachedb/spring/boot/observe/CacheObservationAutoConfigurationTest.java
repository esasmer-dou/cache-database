package com.reactor.cachedb.spring.boot.observe;

import static org.assertj.core.api.Assertions.assertThat;
import com.reactor.cachedb.core.observe.*;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CacheObservationAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheObservationAutoConfiguration.class));

    @Test void absentRegistryHasNoRecorderAndNormalRuntimeUsesCoreNoop() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CacheObservationRecorder.class));
    }
    @Test void optionalObservationClassesCanBeAbsent() {
        runner.withClassLoader(new FilteredClassLoader("io.micrometer.observation"))
                .run(context -> assertThat(context).doesNotHaveBean(CacheObservationRecorder.class));
    }
    @Test void registryEnablesActualAdapterAndPropertyCanDisableIt() {
        runner.withBean(ObservationRegistry.class, ObservationRegistry::create).run(context -> {
            assertThat(context).hasSingleBean(CacheObservationRecorder.class);
            assertThat(context.getBean(CacheObservationRecorder.class)).isInstanceOf(MicrometerCacheObservationRecorder.class);
        });
        runner.withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withPropertyValues("cachedb.observation.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CacheObservationRecorder.class));
    }
    @Test void consumerRecorderIsNeverReplaced() {
        runner.withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withBean(CacheObservationRecorder.class, () -> NoOpCacheObservationRecorder.INSTANCE)
                .run(context -> assertThat(context.getBean(CacheObservationRecorder.class)).isSameAs(NoOpCacheObservationRecorder.INSTANCE));
    }
}
