package com.reactor.cachedb.spring.boot.observe;

import com.reactor.cachedb.core.observe.CacheObservationRecorder;
import com.reactor.cachedb.spring.boot.CacheRuntimeOptionsCustomizer;
import com.reactor.cachedb.spring.boot.CacheDatabaseSpringBootAutoConfiguration;
import com.reactor.cachedb.spring.boot.CacheTraceAutoConfiguration;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = CacheTraceAutoConfiguration.class, before = CacheDatabaseSpringBootAutoConfiguration.class,
        afterName = "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration")
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "cachedb.observation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheObservationAutoConfiguration {
    @Bean @ConditionalOnMissingBean(name = "cacheObservationRuntimeOptionsCustomizer")
    CacheRuntimeOptionsCustomizer cacheObservationRuntimeOptionsCustomizer(CacheObservationRecorder recorder,
            org.springframework.beans.factory.ObjectProvider<com.reactor.cachedb.core.observe.CacheTraceparentSupplier> suppliers) {
        return (builder, properties) -> {
            builder.observationRecorder(recorder);
            var supplier = suppliers.getIfAvailable();
            if (supplier != null) builder.observationTraceparentSupplier(supplier);
        };
    }
    @Bean @ConditionalOnMissingBean
    CacheObservationTagPolicy cacheObservationTagPolicy() { return CacheObservationTagPolicy.defaults(); }
    @Bean @ConditionalOnMissingBean(CacheObservationRecorder.class)
    CacheObservationRecorder cacheObservationRecorder(ObservationRegistry registry, CacheObservationTagPolicy tags) {
        return new MicrometerCacheObservationRecorder(registry, tags);
    }
}
