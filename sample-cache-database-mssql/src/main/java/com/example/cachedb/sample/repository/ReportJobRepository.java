package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.ReportJobEntity;
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

@CacheRepository(entity = ReportJobEntity.class)
public interface ReportJobRepository extends CacheDbRepository<ReportJobEntity, Long> {

    @HotRoute(value = "live-report-jobs", pageSize = 50, hotWindow = 500,
            memoryBudgetBytes = 4_194_304L)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "status", operator = CachePredicate.Operator.IN,
                    constants = {"QUEUED", "RUNNING", "FAILED"}),
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "reportJobId", direction = CacheOrder.Direction.DESC)
            },
            limitParameter = "limit"
    )
    HotWindow<ReportJobEntity> live(int limit);

    @HotRoute(value = "report-jobs-by-type", pageSize = 50, hotWindow = 500,
            coverageScopeParameter = "reportType")
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "reportType", parameter = "reportType"),
            orderBy = {
                    @CacheOrder(field = "createdAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "reportJobId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    HotWindow<ReportJobEntity> byType(String reportType, WindowRequest window);

    @WarmRoute(value = "warm-live-report-jobs", from = "live", maxRows = 500,
            maxRowsParameter = "maxRows")
    CacheWarmPlan warmLive(int maxRows);

    @WarmRoute(value = "warm-report-jobs-by-type", from = "byType", maxRows = 500,
            maxRowsParameter = "maxRows", coverageScopeParameter = "reportType")
    CacheWarmPlan warmByType(String reportType, int maxRows);
}
