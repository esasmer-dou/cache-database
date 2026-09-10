package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.CustomerEntity;
import com.reactor.cachedb.annotations.CacheLookup;
import com.reactor.cachedb.annotations.CacheMemoryBudget;
import com.reactor.cachedb.annotations.CacheOrder;
import com.reactor.cachedb.annotations.CachePredicate;
import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.annotations.CacheRepositoryDefaults;
import com.reactor.cachedb.annotations.CacheRouteQuery;
import com.reactor.cachedb.annotations.HotRoute;
import com.reactor.cachedb.annotations.WarmRoute;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.starter.CacheWarmPlan;

@CacheRepository(entity = CustomerEntity.class)
@CacheRepositoryDefaults(hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500, sourceTimeoutSeconds = 15)
public interface CustomerRepository extends CacheDbRepository<CustomerEntity, Long> {

    @CacheLookup(relation = "orders", maxRelationRows = 25)
    HotLookup<CustomerEntity> detail(Long customerId, int orderPreview);

    @HotRoute(value = "active-customers",
            pageSize = 100, hotWindow = 50_000,
            memoryBudgetBytes = CacheMemoryBudget.MIB_32)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "status", constants = "ACTIVE"),
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "customerId")
            }
    )
    HotWindow<CustomerEntity> active(int limit);

    @WarmRoute(value = "warm-active-customers", from = "active", maxRows = 1_000)
    CacheWarmPlan warmActive(int maxRows);
}
