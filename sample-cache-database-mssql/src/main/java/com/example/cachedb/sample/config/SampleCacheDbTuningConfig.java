package com.example.cachedb.sample.config;

import com.reactor.cachedb.core.config.ReadShapeGuardrailConfig;
import com.reactor.cachedb.core.config.ReadThroughConfig;
import com.reactor.cachedb.core.config.ReadThroughMode;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.spring.boot.CacheDatabaseConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SampleCacheDbTuningConfig {

    @Bean
    CacheDatabaseConfigCustomizer sampleCacheDbTuning() {
        return (builder, properties) -> builder
                .resourceLimits(ResourceLimits.builder()
                        .defaultCachePolicy(SampleCachePolicies.platformDefaultPolicy())
                        .maxRegisteredEntities(128)
                        .maxColumnsPerOperation(64)
                        .build())
                .readShapeGuardrail(ReadShapeGuardrailConfig.builder()
                        .enabled(true)
                        .maxEntityQueryLimit(250)
                        .maxProjectionQueryLimit(1_000)
                        .hotSetHeadroom(10)
                        .build())
                .readThrough(ReadThroughConfig.builder()
                        .mode(ReadThroughMode.READ_THROUGH_QUERY)
                        .failOnMissingLoader(true)
                        .hydrateLoadedEntities(true)
                        .maxQueryLoadRows(500)
                        .queryTimeoutSeconds(15)
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
                        .statementTimeoutSeconds(20)
                        .build());
    }
}
