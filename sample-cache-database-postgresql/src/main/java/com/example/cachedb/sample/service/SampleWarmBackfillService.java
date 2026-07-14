package com.example.cachedb.sample.service;

import com.example.cachedb.sample.domain.AuditEventEntityCacheBinding;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderEntityCacheBinding;
import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.example.cachedb.sample.domain.ReportJobEntityCacheBinding;
import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEntityCacheBinding;
import com.example.cachedb.sample.domain.SupportTicketEntityCacheBinding;
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
    private final EntityRepository<ProductEntity, Long> productRepository;
    private final EntityRepository<ShipmentEntity, Long> shipmentRepository;

    public SampleWarmBackfillService(
            CacheDatabase cacheDatabase,
            EntityRepository<OrderEntity, Long> orderRepository,
            EntityRepository<ProductEntity, Long> productRepository,
            EntityRepository<ShipmentEntity, Long> shipmentRepository
    ) {
        this.cacheDatabase = cacheDatabase;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.shipmentRepository = shipmentRepository;
    }

    public WarmResult warmCustomerOrders(long customerId, int limit, boolean projectionOnly, boolean dryRun) {
        QuerySpec querySpec = QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
        return warm(
                "customer-orders",
                "customerId=" + customerId,
                OrderEntityCacheBinding.METADATA.entityName(),
                querySpec,
                limit,
                projectionOnly,
                dryRun,
                orderRepository
        );
    }

    public WarmResult warmActiveProducts(String category, int limit, boolean projectionOnly, boolean dryRun) {
        QuerySpec query = QuerySpec.where(QueryFilter.eq("active_status", "ACTIVE"));
        if (category != null && !category.isBlank()) {
            query = query.and(QueryFilter.eq("category", category.trim()));
        }
        QuerySpec querySpec = query.orderBy(QuerySort.asc("sku")).limitTo(limit);
        return warm(
                "active-products",
                category == null || category.isBlank() ? "all-categories" : "category=" + category.trim(),
                ProductEntityCacheBinding.METADATA.entityName(),
                querySpec,
                limit,
                projectionOnly,
                dryRun,
                productRepository
        );
    }

    public WarmResult warmOpenTickets(int limit, boolean dryRun) {
        QuerySpec querySpec = QuerySpec.where(QueryFilter.eq("status", "OPEN"))
                .orderBy(QuerySort.desc("updated_at"), QuerySort.asc("ticket_id"))
                .limitTo(limit);
        return warmEntity(
                "open-tickets",
                "status=OPEN",
                SupportTicketEntityCacheBinding.METADATA.entityName(),
                querySpec,
                limit,
                dryRun
        );
    }

    public WarmResult warmActiveShipments(int limit, boolean projectionOnly, boolean dryRun) {
        QuerySpec querySpec = QuerySpec.where(QueryFilter.in(
                        "shipment_status",
                        List.<Object>of("IN_TRANSIT", "OUT_FOR_DELIVERY", "DELAYED", "EXCEPTION")
                ))
                .orderBy(QuerySort.desc("risk_score"), QuerySort.desc("updated_at"), QuerySort.desc("shipment_id"))
                .limitTo(limit);
        return warm(
                "active-shipments",
                "operational-statuses",
                ShipmentEntityCacheBinding.METADATA.entityName(),
                querySpec,
                limit,
                projectionOnly,
                dryRun,
                shipmentRepository
        );
    }

    public WarmResult warmLiveReportJobs(int limit, boolean dryRun) {
        QuerySpec querySpec = QuerySpec.where(QueryFilter.in(
                        "status",
                        List.<Object>of("QUEUED", "RUNNING", "FAILED")
                ))
                .orderBy(QuerySort.desc("updated_at"), QuerySort.desc("report_job_id"))
                .limitTo(limit);
        return warmEntity(
                "live-report-jobs",
                "status=QUEUED|RUNNING|FAILED",
                ReportJobEntityCacheBinding.METADATA.entityName(),
                querySpec,
                limit,
                dryRun
        );
    }

    public WarmResult warmSecurityAudit(int limit, boolean dryRun) {
        QuerySpec querySpec = QuerySpec.where(QueryFilter.in(
                        "severity",
                        List.<Object>of("WARN", "ERROR", "SECURITY")
                ))
                .orderBy(QuerySort.desc("created_at"), QuerySort.desc("audit_event_id"))
                .limitTo(limit);
        return warmEntity(
                "security-audit",
                "severity=WARN|ERROR|SECURITY",
                AuditEventEntityCacheBinding.METADATA.entityName(),
                querySpec,
                limit,
                dryRun
        );
    }

    private WarmResult warmEntity(
            String route,
            String scope,
            String entityName,
            QuerySpec querySpec,
            int limit,
            boolean dryRun
    ) {
        return warm(route, scope, entityName, querySpec, limit, false, dryRun, null);
    }

    private <T, ID> WarmResult warm(
            String route,
            String scope,
            String entityName,
            QuerySpec querySpec,
            int limit,
            boolean projectionOnly,
            boolean dryRun,
            EntityRepository<T, ID> projectionSourceRepository
    ) {
        if (dryRun || projectionOnly) {
            List<T> entities = loadFromRegisteredJdbcSource(entityName, querySpec);
            int submittedRows = 0;
            if (!dryRun && projectionOnly && !entities.isEmpty()) {
                redisRepository(projectionSourceRepository).hydrateProjectionWarmBatch(entities);
                submittedRows = entities.size();
            }
            return new WarmResult(
                    route,
                    scope,
                    limit,
                    entities.size(),
                    submittedRows,
                    0,
                    projectionOnly,
                    dryRun,
                    "registered-jdbc-loader",
                    dryRun
                            ? List.of("Dry run read SQL through the registered EntityQueryLoader and did not mutate Redis.")
                            : List.of("Projection warm loaded the bounded SQL window and refreshed projection rows only.")
            );
        }

        CacheWarmResult result = cacheDatabase.warm(CacheWarmPlan.builder(entityName)
                .name("sample-" + route)
                .querySpec(querySpec)
                .maxRows(limit)
                .forceImmediateProjectionRefresh(true)
                .reindexQueryIndexes(true)
                .build());
        return new WarmResult(
                route,
                scope,
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

    @SuppressWarnings("unchecked")
    private <T> List<T> loadFromRegisteredJdbcSource(String entityName, QuerySpec querySpec) {
        EntityBinding<T, ?> binding = (EntityBinding<T, ?>) cacheDatabase.entityRegistry()
                .find(entityName)
                .orElseThrow(() -> new IllegalStateException(entityName + " must be registered before warm execution"));
        EntityQueryLoader<T> queryLoader = binding.queryLoader();
        if (queryLoader == null || queryLoader instanceof NoOpEntityQueryLoader) {
            throw new IllegalStateException(entityName + " requires registerJdbcBacked(...) for warm execution");
        }
        return queryLoader.load(querySpec);
    }

    @SuppressWarnings("unchecked")
    private <T, ID> RedisEntityRepository<T, ID> redisRepository(EntityRepository<T, ID> repository) {
        if (repository instanceof RedisEntityRepository<?, ?> redisRepository) {
            return (RedisEntityRepository<T, ID>) redisRepository;
        }
        throw new IllegalStateException("Projection-only warm requires RedisEntityRepository");
    }

    public record WarmResult(
            String route,
            String scope,
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
