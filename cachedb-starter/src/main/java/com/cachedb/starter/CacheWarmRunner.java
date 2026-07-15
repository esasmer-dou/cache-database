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
        return execute(plan, WarmMode.ENTITY_AND_PROJECTIONS);
    }

    public CacheWarmResult dryRun(CacheWarmPlan plan) {
        return execute(plan, WarmMode.DRY_RUN);
    }

    public CacheWarmResult executeProjectionsOnly(CacheWarmPlan plan) {
        return execute(plan, WarmMode.PROJECTIONS_ONLY);
    }

    private CacheWarmResult execute(CacheWarmPlan plan, WarmMode mode) {
        CacheWarmPlan normalized = Objects.requireNonNull(plan, "plan");
        EntityBinding<?, ?> rawBinding = cacheDatabase.entityRegistry()
                .find(normalized.entityName())
                .orElseThrow(() -> new IllegalStateException("No registered CacheDB entity found for warm plan entity: "
                        + normalized.entityName()));
        return executeTyped(normalized, rawBinding, mode);
    }

    @SuppressWarnings("unchecked")
    private <T, ID> CacheWarmResult executeTyped(
            CacheWarmPlan plan,
            EntityBinding<?, ?> rawBinding,
            WarmMode mode
    ) {
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
                    List.of(mode == WarmMode.DRY_RUN
                            ? "Warm dry run returned no rows and did not mutate Redis."
                            : "Warm source returned no rows.")
            );
        }
        if (loaded.size() > plan.maxRows()) {
            throw new IllegalArgumentException("Warm plan " + plan.name()
                    + " loader returned " + loaded.size()
                    + " rows but maxRows=" + plan.maxRows());
        }

        if (mode == WarmMode.DRY_RUN) {
            return new CacheWarmResult(
                    plan.name(),
                    plan.entityName(),
                    loaded.size(),
                    0,
                    elapsedMillis(startedAtNanos),
                    false,
                    false,
                    List.of("Warm dry run read the registered JDBC source and did not mutate Redis.")
            );
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
        List<T> entities = loaded.stream().map(VersionedEntity::entity).toList();
        if (mode == WarmMode.PROJECTIONS_ONLY) {
            redisRepository.hydrateProjectionWarmBatch(entities);
        } else {
            redisRepository.hydrateWarmBatch(
                    entities,
                    loaded.stream().map(VersionedEntity::version).toList(),
                    plan.forceImmediateProjectionRefresh(),
                    plan.reindexQueryIndexes()
            );
        }
        return new CacheWarmResult(
                plan.name(),
                plan.entityName(),
                loaded.size(),
                loaded.size(),
                elapsedMillis(startedAtNanos),
                mode == WarmMode.ENTITY_AND_PROJECTIONS && plan.forceImmediateProjectionRefresh(),
                mode == WarmMode.ENTITY_AND_PROJECTIONS && plan.reindexQueryIndexes(),
                List.of(mode == WarmMode.PROJECTIONS_ONLY
                        ? "Rows were submitted to projection-only Redis hydration; full entities were not retained."
                        : "Rows were submitted to Redis warm hydration. Hot policy and tenant quota may still reject individual rows.")
        );
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private enum WarmMode {
        ENTITY_AND_PROJECTIONS,
        PROJECTIONS_ONLY,
        DRY_RUN
    }
}
