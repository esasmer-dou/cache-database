package com.example.cachedb.sample.service;

import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderEntityCacheBinding;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.page.NoOpEntityQueryLoader;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.redis.RedisEntityRepository;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheWarmPlan;
import com.reactor.cachedb.starter.CacheWarmResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SampleWarmBackfillService {

    private final CacheDatabase cacheDatabase;
    private final EntityRepository<OrderEntity, Long> orderRepository;

    public SampleWarmBackfillService(
            CacheDatabase cacheDatabase,
            EntityRepository<OrderEntity, Long> orderRepository
    ) {
        this.cacheDatabase = cacheDatabase;
        this.orderRepository = orderRepository;
    }

    public WarmResult warmCustomerOrders(long customerId, int requestedLimit, boolean projectionOnly, boolean dryRun) {
        int limit = clamp(requestedLimit, 1, 1_000);
        QuerySpec querySpec = customerOrderWindowSpec(customerId, limit);
        if (dryRun || projectionOnly) {
            List<OrderEntity> orders = loadFromRegisteredJdbcSource(querySpec);
            int submittedRows = 0;
            RedisEntityRepository<OrderEntity, Long> redisRepository = redisOrderRepository();
            if (!dryRun && !orders.isEmpty()) {
                redisRepository.hydrateProjectionWarmBatch(orders);
                submittedRows = orders.size();
            }
            return new WarmResult(
                    customerId,
                    limit,
                    orders.size(),
                    submittedRows,
                    0,
                    projectionOnly,
                    dryRun,
                    "registered-jdbc-loader",
                    dryRun
                            ? List.of("Dry run used the registered JDBC EntityQueryLoader and did not mutate Redis.")
                            : List.of("Projection warm used the registered JDBC EntityQueryLoader and refreshed projection rows.")
            );
        }
        CacheWarmResult result = cacheDatabase.warm(CacheWarmPlan.builder(OrderEntityCacheBinding.METADATA.entityName())
                .name("sample-customer-order-window-" + customerId)
                .querySpec(querySpec)
                .maxRows(limit)
                .forceImmediateProjectionRefresh(true)
                .reindexQueryIndexes(true)
                .build());
        return new WarmResult(
                customerId,
                limit,
                result.loadedRows(),
                result.submittedRows(),
                result.durationMillis(),
                false,
                false,
                "cache-warm-plan",
                result.notes()
        );
    }

    private QuerySpec customerOrderWindowSpec(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
    }

    @SuppressWarnings("unchecked")
    private List<OrderEntity> loadFromRegisteredJdbcSource(QuerySpec querySpec) {
        EntityBinding<OrderEntity, Long> binding = (EntityBinding<OrderEntity, Long>) cacheDatabase.entityRegistry()
                .find(OrderEntityCacheBinding.METADATA.entityName())
                .orElseThrow(() -> new IllegalStateException("OrderEntity must be registered before warm execution"));
        EntityQueryLoader<OrderEntity> queryLoader = binding.queryLoader();
        if (queryLoader == null || queryLoader instanceof NoOpEntityQueryLoader) {
            throw new IllegalStateException("OrderEntity warm requires registerJdbcBacked(...) so the JDBC query loader is available");
        }
        return queryLoader.load(querySpec);
    }

    @SuppressWarnings("unchecked")
    private RedisEntityRepository<OrderEntity, Long> redisOrderRepository() {
        if (orderRepository instanceof RedisEntityRepository<?, ?> redisRepository) {
            return (RedisEntityRepository<OrderEntity, Long>) redisRepository;
        }
        throw new IllegalStateException("Warm backfill requires RedisEntityRepository");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record WarmResult(
            long customerId,
            int requestedWindow,
            int rowsReadFromSql,
            int rowsSubmittedToRedis,
            long durationMillis,
            boolean projectionOnly,
            boolean dryRun,
            String source,
            List<String> notes
    ) {
    }
}
