package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;

import java.util.List;

public final class CacheDatabaseProfiles {

    private CacheDatabaseProfiles() {
    }

    public static CacheDatabaseConfig development() {
        return CacheDatabaseConfig.builder()
                .adminMonitoring(AdminMonitoringConfig.builder()
                        .enabled(true)
                        .build())
                .adminHttp(AdminHttpConfig.builder()
                        .enabled(true)
                        .dashboardEnabled(true)
                        .build())
                .schemaBootstrap(SchemaBootstrapConfig.builder()
                        .mode(SchemaBootstrapMode.CREATE_IF_MISSING)
                        .autoApplyOnStart(true)
                        .build())
                .build();
    }

    public static CacheDatabaseConfig production() {
        return CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .workerThreads(Math.max(2, WriteBehindConfig.defaults().workerThreads()))
                        .batchFlushEnabled(true)
                        .tableAwareBatchingEnabled(true)
                        .durableCompactionEnabled(true)
                        .build())
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .enabled(true)
                        .producerBackpressureEnabled(true)
                        .build())
                .schemaBootstrap(SchemaBootstrapConfig.builder()
                        .mode(SchemaBootstrapMode.VALIDATE_ONLY)
                        .autoApplyOnStart(true)
                        .build())
                .build();
    }

    public static CacheDatabaseConfig benchmark() {
        return CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .batchFlushEnabled(true)
                        .tableAwareBatchingEnabled(true)
                        .durableCompactionEnabled(true)
                        .postgresMultiRowFlushEnabled(true)
                        .postgresCopyBulkLoadEnabled(true)
                        .build())
                .schemaBootstrap(SchemaBootstrapConfig.builder()
                        .mode(SchemaBootstrapMode.CREATE_IF_MISSING)
                        .autoApplyOnStart(true)
                        .build())
                .build();
    }

    public static CacheDatabaseConfig memoryConstrained() {
        return CacheDatabaseConfig.builder()
                .resourceLimits(com.reactor.cachedb.core.config.ResourceLimits.builder()
                        .defaultCachePolicy(CachePolicy.builder()
                                .hotEntityLimit(128)
                                .pageSize(32)
                                .entityTtlSeconds(900)
                                .pageTtlSeconds(120)
                                .build())
                        .build())
                .redisGuardrail(RedisGuardrailConfig.builder()
                        .enabled(true)
                        .producerBackpressureEnabled(true)
                        .usedMemoryWarnBytes(256L * 1024L * 1024L)
                        .usedMemoryCriticalBytes(384L * 1024L * 1024L)
                        .shedPageCacheWritesOnHardLimit(true)
                        .shedReadThroughCacheOnHardLimit(true)
                        .shedHotSetTrackingOnHardLimit(true)
                        .shedQueryIndexWritesOnHardLimit(true)
                        .shedQueryIndexReadsOnHardLimit(true)
                        .shedPlannerLearningOnHardLimit(true)
                        .build())
                .build();
    }

    public static CacheDatabaseConfig minimalOverhead() {
        return CacheDatabaseConfig.builder()
                .adminMonitoring(AdminMonitoringConfig.builder()
                        .enabled(false)
                        .build())
                .adminHttp(AdminHttpConfig.builder()
                        .enabled(false)
                        .dashboardEnabled(false)
                        .build())
                .build();
    }

    public static List<StarterProfileSnapshot> catalog() {
        return List.of(
                snapshot("development", development(), "Fast local bootstrap with admin dashboard enabled."),
                snapshot("production", production(), "Validated runtime defaults with strict schema validation."),
                snapshot("benchmark", benchmark(), "High-throughput profile for certification and soak runners."),
                snapshot("memoryConstrained", memoryConstrained(), "Guardrail-heavy profile for low-memory deployments."),
                snapshot("minimalOverhead", minimalOverhead(), "Disables admin monitoring and admin HTTP surfaces for library-first embedding.")
        );
    }

    private static StarterProfileSnapshot snapshot(String name, CacheDatabaseConfig config, String note) {
        return new StarterProfileSnapshot(
                name,
                config.adminHttp().enabled(),
                config.adminHttp().dashboardEnabled(),
                config.writeBehind().enabled(),
                config.redisGuardrail().enabled(),
                config.schemaBootstrap().mode().name(),
                config.schemaBootstrap().autoApplyOnStart(),
                note
        );
    }
}
