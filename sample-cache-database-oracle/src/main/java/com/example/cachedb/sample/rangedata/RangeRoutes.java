package com.example.cachedb.sample.rangedata;

import com.reactor.cachedb.annotations.*;
import com.reactor.cachedb.core.repository.*;

@CacheRepository(entity = RangeDataRow.class, springBean = false)
public interface RangeRoutes extends CacheDbRepository<RangeDataRow, String> {
    @HotRoute(value = "sample-range-asc", coverageScopeParameter = "runId", pageSize = 32,
            population = HotRoute.Population.WRITE_FED)
    @CacheRouteQuery(redisLexicalIdTie = true, redisCompositeIndex = "tenant_run_rank",
            predicates = {@CachePredicate(field = "tenantId"),
                    @CachePredicate(field = "runId"),
                    @CachePredicate(field = "status", constants = "OPEN"),
                    @CachePredicate(field = "rankValue", parameter = "minimum", operator = CachePredicate.Operator.GTE),
                    @CachePredicate(field = "rankValue", parameter = "maximum", operator = CachePredicate.Operator.LTE)},
            orderBy = @CacheOrder(field = "rankValue", direction = CacheOrder.Direction.ASC))
    HotWindow<RangeDataRow> ascending(String tenantId, String runId, Long minimum, Long maximum, WindowRequest window);

    @HotRoute(value = "sample-range-desc", coverageScopeParameter = "runId", pageSize = 32,
            population = HotRoute.Population.WRITE_FED)
    @CacheRouteQuery(redisLexicalIdTie = true, redisCompositeIndex = "tenant_run_rank",
            predicates = {@CachePredicate(field = "tenantId"),
                    @CachePredicate(field = "runId"),
                    @CachePredicate(field = "status", constants = "OPEN"),
                    @CachePredicate(field = "rankValue", parameter = "minimum", operator = CachePredicate.Operator.GTE),
                    @CachePredicate(field = "rankValue", parameter = "maximum", operator = CachePredicate.Operator.LTE)},
            orderBy = @CacheOrder(field = "rankValue", direction = CacheOrder.Direction.DESC))
    HotWindow<RangeDataRow> descending(String tenantId, String runId, Long minimum, Long maximum, WindowRequest window);

    @HotRoute(value = "sample-range-seek", coverageScopeParameter = "runId", pageSize = 32,
            population = HotRoute.Population.WRITE_FED)
    @CacheRouteQuery(redisLexicalIdTie = true, redisCompositeIndex = "tenant_run_rank",
            predicates = {@CachePredicate(field = "tenantId"),
                    @CachePredicate(field = "runId"),
                    @CachePredicate(field = "status", constants = "OPEN"),
                    @CachePredicate(field = "rankValue", parameter = "minimum", operator = CachePredicate.Operator.GTE),
                    @CachePredicate(field = "rankValue", parameter = "maximum", operator = CachePredicate.Operator.LTE)},
            orderBy = @CacheOrder(field = "rankValue", direction = CacheOrder.Direction.ASC),
            seek = {@CacheSeek(field = "rankValue", param = "afterValue", direction = CachePredicate.Operator.GT),
                    @CacheSeek(field = "rowId", param = "afterId", direction = CachePredicate.Operator.GT)})
    HotWindow<RangeDataRow> seek(String tenantId, String runId, Long minimum, Long maximum,
            WindowRequest window, Long afterValue, String afterId);
}
