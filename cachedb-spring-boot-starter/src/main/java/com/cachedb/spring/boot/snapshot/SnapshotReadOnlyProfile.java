package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;

/** SQL yazmayi ve istek sirasinda SQL okumayi kapatan uygulama profilini tanimlar. */
public final class SnapshotReadOnlyProfile {
    public static final long RETENTION_SECONDS = 3_600;
    public static final int PUBLICATION_BATCH_SIZE = 256;

    /** Yalnizca ortak tanimlar iceren bu sinifin orneklenmesini engeller. */
    private SnapshotReadOnlyProfile() {}

    /**
     * Redis-only okumayi ve projection saklama ayarlarini uygular. Buyuk JSON alanlari icin sorgu
     * indeksi kurmaz; okuma siniri toplam katalogu degil tek yayin partisini kapsar.
     */
    public static void configure(CacheDatabaseConfig.Builder builder, String keyPrefix) {
        if (keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9:_-]{1,100}")) {
            throw new IllegalArgumentException("Invalid snapshot cache key prefix");
        }
        builder.keyspace(KeyspaceConfig.builder().keyPrefix(keyPrefix).build())
                .writeBehind(WriteBehindConfig.builder().enabled(false).build())
                .writeBehindFlusherFactory(
                        (ds, registry, config, metrics) ->
                                operation -> {
                                    throw new java.sql.SQLException(
                                            "Snapshot read models cannot write to the source"
                                                    + " database");
                                })
                .deadLetterRecovery(
                        DeadLetterRecoveryConfig.builder()
                                .enabled(false)
                                .cleanupEnabled(false)
                                .build())
                .projectionRefresh(ProjectionRefreshConfig.builder().enabled(false).build())
                .adminMonitoring(AdminMonitoringConfig.builder().enabled(false).build())
                .schemaBootstrap(
                        SchemaBootstrapConfig.builder()
                                .mode(SchemaBootstrapMode.DISABLED)
                                .autoApplyOnStart(false)
                                .build())
                .resourceLimits(ResourceLimits.builder().defaultCachePolicy(policy()).build())
                .readThrough(
                        ReadThroughConfig.builder()
                                .mode(ReadThroughMode.REDIS_ONLY)
                                .hydrateLoadedEntities(false)
                                .maxQueryLoadRows(PUBLICATION_BATCH_SIZE)
                                .queryTimeoutSeconds(30)
                                .build())
                // Okuma yalnizca kimlikle yapilir; buyuk JSON icerikleri icin sorgu indeksi
                // tutulmaz.
                .queryIndex(
                        QueryIndexConfig.builder()
                                .exactIndexEnabled(false)
                                .rangeIndexEnabled(false)
                                .prefixIndexEnabled(false)
                                .textIndexEnabled(false)
                                .plannerStatisticsEnabled(false)
                                .plannerStatisticsPersisted(false)
                                .build());
    }

    /**
     * Kayit sayisina gore tahliyeyi kapatir; 60 dakikalik TTL, 30 dakikalik sunum sinirindan
     * ayridir.
     */
    public static CachePolicy policy() {
        return CachePolicy.builder()
                .hotEntityLimit(0)
                .lruEvictionEnabled(false)
                .entityTtlSeconds(RETENTION_SECONDS)
                .build();
    }
}
