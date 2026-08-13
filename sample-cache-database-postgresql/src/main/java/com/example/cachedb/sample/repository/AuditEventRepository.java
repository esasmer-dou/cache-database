package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.AuditEventEntity;
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
import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.starter.CacheWarmPlan;

@CacheRepository(entity = AuditEventEntity.class)
@CacheRepositoryDefaults(hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500, sourceTimeoutSeconds = 15)
public interface AuditEventRepository extends CacheDbRepository<AuditEventEntity, Long> {

    @HotRoute(value = "security-audit-events",
            pageSize = 50, hotWindow = 500,
            memoryBudgetBytes = CacheMemoryBudget.MIB_4)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "severity", operator = CachePredicate.Operator.IN,
                    constants = {"WARN", "ERROR", "SECURITY"}),
            orderBy = {
                    @CacheOrder(field = "createdAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "auditEventId", direction = CacheOrder.Direction.DESC)
            }
    )
    HotWindow<AuditEventEntity> security(int limit);

    @SourceRoute(value = "entity-audit-archive", maxRows = 500, timeoutSeconds = 15)
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "entityName"),
                    @CachePredicate(field = "entityId")
            },
            orderBy = {
                    @CacheOrder(field = "createdAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "auditEventId", direction = CacheOrder.Direction.DESC)
            }
    )
    CursorPage<AuditEventEntity> archive(String entityName, long entityId, WindowRequest window);

    @WarmRoute(value = "warm-security-audit", from = "security", maxRows = 500)
    CacheWarmPlan warmSecurity(int maxRows);
}
