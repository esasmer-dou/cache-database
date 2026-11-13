package com.example.cachedb.sample.audit;

import com.reactor.cachedb.core.config.ReadThroughMode;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.retention.RetentionTarget;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.time.Clock;
import java.util.List;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
@ConditionalOnProperty(name = "sample.audit-api.enabled", havingValue = "true")
public class AuditConfiguration {
    @Bean AuditStores auditStores(CacheDatabase database) {
        if (database.config().readThrough().mode() != ReadThroughMode.REDIS_ONLY)
            throw new IllegalStateException("Audit examples require REDIS_ONLY; source reads are explicit");
        var evict = database.repository(EvictAuditNoteCacheBinding.METADATA, EvictAuditNoteCacheBinding.CODEC);
        var delete = database.repository(DeleteAuditNoteCacheBinding.METADATA, DeleteAuditNoteCacheBinding.CODEC);
        if (!(evict instanceof RedisEntityRepository<?, ?> e) || !(delete instanceof RedisEntityRepository<?, ?> d))
            throw new IllegalStateException("Audit examples require native Redis repositories");
        var sweeper = new RetentionSweeper(List.of(
                target(database, EvictAuditNoteCacheBinding.METADATA, EvictAuditNoteCacheBinding.CODEC, e),
                target(database, DeleteAuditNoteCacheBinding.METADATA, DeleteAuditNoteCacheBinding.CODEC, d)));
        return new AuditStores(evict, delete,
                database.sourceRepository(EvictAuditNoteCacheBinding.METADATA, EvictAuditNoteCacheBinding.CODEC),
                database.sourceRepository(DeleteAuditNoteCacheBinding.METADATA, DeleteAuditNoteCacheBinding.CODEC),
                new DeleteAuditNotesCacheDbImplementation(database), sweeper, database);
    }
    private static <T> RetentionTarget target(CacheDatabase database, EntityMetadata<T, String> metadata,
            EntityCodec<T> codec, RedisEntityRepository<?, ?> repository) {
        if (metadata.tenantPurgeEnabled()) throw new IllegalArgumentException("Only ordinary audit entities are supported");
        var config = database.config();
        var k = config.keyspace();
        RedisKeyStrategy keys = database.redisOperations().clustered() || database.backgroundRedisOperations().clustered()
                ? new ClusterHashTagKeyStrategy(k)
                : new RedisKeyStrategy(k.keyPrefix(), k.entitySegment(), k.pageSegment(), k.versionSegment(),
                        k.tombstoneSegment(), k.hotSetSegment(), k.indexSegment(), k.compactionSegment());
        var policy = repository.cachePolicy();
        var local = policy.nearCachePolicy().enabled()
                ? database.nearCacheCoordinator().register(metadata.redisNamespace(), policy.nearCachePolicy()) : null;
        var runtime = database.runtimeOptions();
        var trace = runtime.observationRecorder().enabled()
                && runtime.observationTraceparentSupplier() != com.reactor.cachedb.core.observe.CacheTraceparentSupplier.NONE
                ? new com.reactor.cachedb.core.observe.ObservingTraceContextPropagator(
                        config.traceContextPropagator(), runtime.observationTraceparentSupplier(), true)
                : config.traceContextPropagator();
        var mapper = new RedisFunctionArgsMapper(trace).withNearInvalidationChannel(keys.nearInvalidationChannel())
                .withNearInvalidationPublisher(database::publishLegacyNearInvalidations);
        var write = config.writeBehind();
        var producer = new RedisProducerGuard(database.backgroundRedisOperations(), config.redisGuardrail(), write,
                keys, config.adminReportJob().diagnosticsStreamKey(), config.adminReportJob().diagnosticsMaxLength());
        return new RedisRetentionAdapter<>(database.redisOperations(), keys, metadata,
                database.runtimeCodec(metadata, codec), Function.identity(), policy, config.queryIndex(),
                config.redisFunctions(), config.redisGuardrail(), write, mapper, producer, local, Clock.systemUTC(),
                new RedisRetentionAdapter.Settings(write.streamKey(), write.compactionStreamKey(),
                        write.compactionShardCount(), 512, 65536, 15000, false));
    }
}
