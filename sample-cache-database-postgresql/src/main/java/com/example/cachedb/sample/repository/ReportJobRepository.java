package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.ReportJobEntity;
import com.reactor.cachedb.annotations.CacheMemoryBudget;
import com.reactor.cachedb.annotations.CacheOrder;
import com.reactor.cachedb.annotations.CachePredicate;
import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.annotations.CacheRepositoryDefaults;
import com.reactor.cachedb.annotations.CacheRouteQuery;
import com.reactor.cachedb.annotations.HotRoute;
import com.reactor.cachedb.annotations.WarmRoute;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.starter.CacheWarmPlan;

@CacheRepository(entity = ReportJobEntity.class)
@CacheRepositoryDefaults(hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500, sourceTimeoutSeconds = 15)
public interface ReportJobRepository extends CacheDbRepository<ReportJobEntity, Long> {

    @HotRoute(value = "live-report-jobs",
            pageSize = 50, hotWindow = 500,
            memoryBudgetBytes = CacheMemoryBudget.MIB_4)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "status", operator = CachePredicate.Operator.IN,
                    constants = {"QUEUED", "RUNNING", "FAILED"}),
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "reportJobId", direction = CacheOrder.Direction.DESC)
            }
    )
    HotWindow<ReportJobEntity> live(int limit);

    @HotRoute(value = "report-jobs-by-type",
            pageSize = 50, hotWindow = 500,
            coverageScopeParameter = "reportType")
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "reportType"),
            orderBy = {
                    @CacheOrder(field = "createdAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "reportJobId", direction = CacheOrder.Direction.DESC)
            }
    )
    CursorPage<ReportJobEntity> byType(String reportType, WindowRequest window);

    @WarmRoute(value = "warm-live-report-jobs", from = "live", maxRows = 500)
    CacheWarmPlan warmLive(int maxRows);

    @WarmRoute(value = "warm-report-jobs-by-type", from = "byType", maxRows = 500)
    CacheWarmPlan warmByType(String reportType, int maxRows);
}
