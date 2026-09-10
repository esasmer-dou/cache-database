package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.SupportTicketEntity;
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

@CacheRepository(entity = SupportTicketEntity.class)
@CacheRepositoryDefaults(hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500, sourceTimeoutSeconds = 15)
public interface SupportTicketRepository extends CacheDbRepository<SupportTicketEntity, Long> {

    @CacheLookup
    HotLookup<SupportTicketEntity> detail(Long ticketId);

    @HotRoute(value = "open-tickets",
            pageSize = 50, hotWindow = 1_000,
            memoryBudgetBytes = CacheMemoryBudget.MIB_8)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "status", constants = "OPEN"),
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "ticketId")
            }
    )
    HotWindow<SupportTicketEntity> open(int limit);

    @WarmRoute(value = "warm-open-tickets", from = "open", maxRows = 1_000)
    CacheWarmPlan warmOpen(int maxRows);
}
