package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.trace.TraceContextPropagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = CacheDatabaseSpringBootAutoConfiguration.class)
@ConditionalOnClass(org.slf4j.MDC.class)
public class CacheTraceAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    TraceContextPropagator cacheTraceContextPropagator() { return new MdcTraceContextPropagator(); }

    @Bean
    CacheDatabaseConfigCustomizer cacheTraceCustomizer(TraceContextPropagator propagator) {
        return (builder, properties) -> builder.traceContextPropagator(propagator);
    }
}
