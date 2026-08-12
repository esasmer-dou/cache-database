package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.OrderLineEntity;
import com.reactor.cachedb.annotations.CacheOrder;
import com.reactor.cachedb.annotations.CachePredicate;
import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.annotations.CacheRepositoryDefaults;
import com.reactor.cachedb.annotations.CacheRouteQuery;
import com.reactor.cachedb.annotations.HotRoute;
import com.reactor.cachedb.annotations.WarmRoute;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.starter.CacheWarmPlan;

@CacheRepository(entity = OrderLineEntity.class)
@CacheRepositoryDefaults(hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500, sourceTimeoutSeconds = 15)
public interface OrderLineRepository extends CacheDbRepository<OrderLineEntity, Long> {

    @HotRoute(value = "order-lines",
            pageSize = 100, hotWindow = 1_000,
            coverageScopeParameter = "orderId")
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "orderId", parameter = "orderId"),
            orderBy = {
                    @CacheOrder(field = "lineNumber"),
                    @CacheOrder(field = "lineId")
            },
            windowParameter = "window"
    )
    HotWindow<OrderLineEntity> forOrder(long orderId, WindowRequest window);

    @WarmRoute(value = "warm-order-lines", from = "forOrder", maxRows = 1_000,
            maxRowsParameter = "maxRows", coverageScopeParameter = "orderId")
    CacheWarmPlan warmForOrder(long orderId, int maxRows);
}
