package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.readmodel.ShipmentSummary;
import com.reactor.cachedb.annotations.CacheLookup;
import com.reactor.cachedb.annotations.CacheMemoryBudget;
import com.reactor.cachedb.annotations.CacheOrder;
import com.reactor.cachedb.annotations.CachePredicate;
import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.annotations.CacheRepositoryDefaults;
import com.reactor.cachedb.annotations.CacheRouteQuery;
import com.reactor.cachedb.annotations.HotRoute;
import com.reactor.cachedb.annotations.SourceRoute;
import com.reactor.cachedb.annotations.WarmRoute;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.repository.SourceWindow;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.starter.CacheWarmPlan;
import com.reactor.cachedb.starter.CacheWarmTarget;

@CacheRepository(entity = ShipmentEntity.class)
@CacheRepositoryDefaults(hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500, sourceTimeoutSeconds = 15)
public interface ShipmentRepository extends CacheDbRepository<ShipmentEntity, Long> {

    @CacheLookup(idParameter = "shipmentId", relation = "events",
            relationLimitParameter = "eventPreview", relationLimit = 20, maxRelationRows = 20)
    HotLookup<ShipmentEntity> detail(Long shipmentId, int eventPreview);

    @HotRoute(value = "active-shipments",
            projection = ShipmentSummary.class,
            pageSize = 100, hotWindow = 10_000, memoryBudgetBytes = CacheMemoryBudget.MIB_32)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "shipmentStatus", operator = CachePredicate.Operator.IN,
                    constants = {"IN_TRANSIT", "OUT_FOR_DELIVERY", "DELAYED", "EXCEPTION"}),
            orderBy = {
                    @CacheOrder(field = "riskScore", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "shipmentId", direction = CacheOrder.Direction.DESC)
            },
            limitParameter = "limit"
    )
    HotWindow<ShipmentSummary> active(int limit);

    @HotRoute(value = "customer-shipments",
            projection = ShipmentSummary.class,
            pageSize = 100, hotWindow = 1_000, memoryBudgetBytes = CacheMemoryBudget.MIB_16,
            coverageScopeParameter = "customerId")
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "customerId", parameter = "customerId"),
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "shipmentId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    HotWindow<ShipmentSummary> forCustomer(long customerId, WindowRequest window);

    @HotRoute(value = "shipment-exceptions",
            projection = ShipmentSummary.class,
            pageSize = 100, hotWindow = 2_000, memoryBudgetBytes = CacheMemoryBudget.MIB_8)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "shipmentStatus", operator = CachePredicate.Operator.IN,
                    constants = {"DELAYED", "EXCEPTION"}),
            orderBy = {
                    @CacheOrder(field = "riskScore", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "shipmentId", direction = CacheOrder.Direction.DESC)
            },
            limitParameter = "limit"
    )
    HotWindow<ShipmentSummary> exceptions(int limit);

    @SourceRoute(value = "delivered-shipment-archive", projection = ShipmentSummary.class,
            maxRows = 500, timeoutSeconds = 15)
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "customerId", parameter = "customerId"),
                    @CachePredicate(field = "shipmentStatus", constants = "DELIVERED")
            },
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "shipmentId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    SourceWindow<ShipmentSummary> deliveredArchive(long customerId, WindowRequest window);

    @WarmRoute(value = "warm-active-shipments", from = "active", maxRows = 1_000,
            maxRowsParameter = "maxRows", targetParameter = "target")
    CacheWarmPlan warmActive(int maxRows, CacheWarmTarget target);

    @WarmRoute(value = "warm-customer-shipments", from = "forCustomer", maxRows = 1_000,
            maxRowsParameter = "maxRows", coverageScopeParameter = "customerId", projectionsOnly = true)
    CacheWarmPlan warmForCustomer(long customerId, int maxRows);

    @WarmRoute(value = "warm-shipment-exceptions", from = "exceptions", maxRows = 1_000,
            maxRowsParameter = "maxRows", projectionsOnly = true)
    CacheWarmPlan warmExceptions(int maxRows);
}
