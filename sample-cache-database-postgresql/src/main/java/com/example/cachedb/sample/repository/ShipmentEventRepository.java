package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.reactor.cachedb.annotations.CacheOrder;
import com.reactor.cachedb.annotations.CachePredicate;
import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.annotations.CacheRouteQuery;
import com.reactor.cachedb.annotations.HotRoute;
import com.reactor.cachedb.annotations.WarmRoute;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.starter.CacheWarmPlan;

@CacheRepository(entity = ShipmentEventEntity.class)
public interface ShipmentEventRepository extends CacheDbRepository<ShipmentEventEntity, Long> {

    @HotRoute(value = "shipment-events", pageSize = 100, hotWindow = 1_000,
            coverageScopeParameter = "shipmentId")
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "shipmentId", parameter = "shipmentId"),
            orderBy = {
                    @CacheOrder(field = "eventTime", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "eventId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    HotWindow<ShipmentEventEntity> forShipment(long shipmentId, WindowRequest window);

    @WarmRoute(value = "warm-shipment-events", from = "forShipment", maxRows = 1_000,
            maxRowsParameter = "maxRows", coverageScopeParameter = "shipmentId")
    CacheWarmPlan warmForShipment(long shipmentId, int maxRows);
}
