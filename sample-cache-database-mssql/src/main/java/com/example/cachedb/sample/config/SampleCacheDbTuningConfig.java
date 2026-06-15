package com.example.cachedb.sample.config;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.EntityHotPolicy;
import com.reactor.cachedb.core.cache.EntityHotPolicyCompositeOperator;
import com.reactor.cachedb.core.config.ReadShapeGuardrailConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.spring.boot.CacheDatabaseConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SampleCacheDbTuningConfig {

    @Bean
    CacheDatabaseConfigCustomizer sampleCacheDbTuning() {
        return (builder, properties) -> builder
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.builder()
                                .hotEntityLimit(5_000)
                                .pageSize(100)
                                .entityTtlSeconds(0)
                                .pageTtlSeconds(120)
                                .compositeHotPolicy(EntityHotPolicyCompositeOperator.ANY, List.of(
                                        EntityHotPolicy.timeWindow("order_date", 90L * 24L * 60L * 60L),
                                        EntityHotPolicy.stateWindow("status", List.of("ACTIVE", "NEW", "PAID", "PICKING", "OPEN", "PENDING")),
                                        EntityHotPolicy.stateWindow("active_status", List.of("ACTIVE"))
                                ))
                                .build())
                        .maxRegisteredEntities(64)
                        .maxColumnsPerOperation(64)
                        .build())
                .readShapeGuardrail(ReadShapeGuardrailConfig.builder()
                        .enabled(true)
                        .maxEntityQueryLimit(250)
                        .maxProjectionQueryLimit(1_000)
                        .hotSetHeadroom(10)
                        .build())
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .enabled(true)
                        .producerBackpressureEnabled(true)
                        .usedMemoryWarnMaxmemoryPercent(75)
                        .usedMemoryCriticalMaxmemoryPercent(88)
                        .expectedMaxmemoryPolicy("noeviction")
                        .warnOnMissingMaxmemory(true)
                        .writeBehindBacklogWarnThreshold(500)
                        .writeBehindBacklogCriticalThreshold(2_000)
                        .automaticRuntimeProfileSwitchingEnabled(true)
                        .build())
                .writeBehind(WriteBehindConfig.builder()
                        .workerThreads(2)
                        .batchSize(128)
                        .maxFlushBatchSize(128)
                        .tableAwareBatchingEnabled(true)
                        .batchFlushEnabled(true)
                        .coalescingEnabled(true)
                        .maxFlushRetries(5)
                        .retryBackoffMillis(500)
                        .build());
    }
}
