package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.page.NoOpEntityQueryLoader;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.page.VersionedEntitySourceLoader;
import com.reactor.cachedb.core.queue.PerformanceObservationContext;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.redis.RedisEntityRepository;

import java.util.List;
import java.util.Objects;

public final class CacheWarmRunner {

    private final CacheDatabase cacheDatabase;

    public CacheWarmRunner(CacheDatabase cacheDatabase) {
        this.cacheDatabase = Objects.requireNonNull(cacheDatabase, "cacheDatabase");
    }

    public CacheWarmResult execute(CacheWarmPlan plan) {
        CacheWarmPlan normalized = Objects.requireNonNull(plan, "plan");
        EntityBinding<?, ?> rawBinding = cacheDatabase.entityRegistry()
                .find(normalized.entityName())
                .orElseThrow(() -> new IllegalStateException("No registered CacheDB entity found for warm plan entity: "
                        + normalized.entityName()));
        return executeTyped(normalized, rawBinding);
    }

    @SuppressWarnings("unchecked")
    private <T, ID> CacheWarmResult executeTyped(CacheWarmPlan plan, EntityBinding<?, ?> rawBinding) {
        EntityBinding<T, ID> binding = (EntityBinding<T, ID>) rawBinding;
        EntityQueryLoader<T> queryLoader = binding.queryLoader();
        if (queryLoader == null || queryLoader instanceof NoOpEntityQueryLoader) {
            throw new IllegalStateException("Warm plan " + plan.name()
                    + " requires an EntityQueryLoader for " + plan.entityName());
        }
        if (!(queryLoader instanceof VersionedEntitySourceLoader<?, ?> rawVersionedLoader)) {
            throw new IllegalStateException("Warm plan " + plan.name()
                    + " requires a VersionedEntitySourceLoader so Redis version fences remain correct");
        }
        VersionedEntitySourceLoader<T, ID> versionedLoader =
                (VersionedEntitySourceLoader<T, ID>) rawVersionedLoader;
        if (plan.querySpec().limit() > plan.maxRows()) {
            throw new IllegalArgumentException("Warm plan " + plan.name()
                    + " query limit " + plan.querySpec().limit()
                    + " exceeds maxRows=" + plan.maxRows());
        }

        long startedAtNanos = System.nanoTime();
        List<VersionedEntity<T>> loaded = PerformanceObservationContext.supplyWithTag(
                "warm:" + plan.name(),
                () -> versionedLoader.loadVersionedQuery(plan.querySpec())
        );
        if (loaded == null || loaded.isEmpty()) {
            return new CacheWarmResult(
                    plan.name(),
                    plan.entityName(),
                    0,
                    0,
                    elapsedMillis(startedAtNanos),
                    plan.forceImmediateProjectionRefresh(),
                    plan.reindexQueryIndexes(),
                    List.of("Warm source returned no rows.")
            );
        }
        if (loaded.size() > plan.maxRows()) {
            throw new IllegalArgumentException("Warm plan " + plan.name()
                    + " loader returned " + loaded.size()
                    + " rows but maxRows=" + plan.maxRows());
        }

        EntityRepository<T, ID> repository = cacheDatabase.repository(
                binding.metadata(),
                binding.codec(),
                binding.cachePolicy()
        );
        if (!(repository instanceof RedisEntityRepository<?, ?> rawRedisRepository)) {
            throw new IllegalStateException("Warm plan " + plan.name()
                    + " requires the Redis entity repository runtime");
        }
        RedisEntityRepository<T, ID> redisRepository = (RedisEntityRepository<T, ID>) rawRedisRepository;
        redisRepository.hydrateWarmBatch(
                loaded.stream().map(VersionedEntity::entity).toList(),
                loaded.stream().map(VersionedEntity::version).toList(),
                plan.forceImmediateProjectionRefresh(),
                plan.reindexQueryIndexes()
        );
        return new CacheWarmResult(
                plan.name(),
                plan.entityName(),
                loaded.size(),
                loaded.size(),
                elapsedMillis(startedAtNanos),
                plan.forceImmediateProjectionRefresh(),
                plan.reindexQueryIndexes(),
                List.of("Rows were submitted to Redis warm hydration. Hot policy and tenant quota may still reject individual rows.")
        );
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }
}
