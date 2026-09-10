package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.readmodel.OrderSummary;
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
import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.starter.CacheWarmPlan;
import com.reactor.cachedb.starter.CacheWarmTarget;

import java.math.BigDecimal;

@CacheRepository(entity = OrderEntity.class)
@CacheRepositoryDefaults(hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500, sourceTimeoutSeconds = 15)
public interface OrderRepository extends CacheDbRepository<OrderEntity, Long> {

    @CacheLookup(relation = "lines", maxRelationRows = 50)
    HotLookup<OrderEntity> detail(Long orderId, int linePreview);

    @HotRoute(value = "customer-order-timeline",
            projection = OrderSummary.class,
            pageSize = 100, hotWindow = 1_000, memoryBudgetBytes = CacheMemoryBudget.MIB_16,
            coverageScopeParameter = "customerId")
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "customerId"),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE, constants = "DELETED")
            },
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            }
    )
    CursorPage<OrderSummary> customerTimeline(long customerId, WindowRequest window);

    @HotRoute(value = "recent-high-value-orders",
            projection = OrderSummary.class,
            pageSize = 100, hotWindow = 5_000, memoryBudgetBytes = CacheMemoryBudget.MIB_32)
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "orderAmount", operator = CachePredicate.Operator.GTE,
                            parameter = "minimumAmount"),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE, constants = "DELETED")
            },
            orderBy = {
                    @CacheOrder(field = "priorityScore", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            }
    )
    CursorPage<OrderSummary> recentHighValue(BigDecimal minimumAmount, WindowRequest window);

    @HotRoute(value = "dashboard-highlighted-orders",
            projection = OrderSummary.class,
            pageSize = 100, hotWindow = 2_000, memoryBudgetBytes = CacheMemoryBudget.MIB_16)
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "priorityScore", operator = CachePredicate.Operator.GTE,
                            parameter = "minimumPriorityScore"),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE, constants = "DELETED")
            },
            orderBy = {
                    @CacheOrder(field = "priorityScore", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            }
    )
    HotWindow<OrderSummary> highlighted(double minimumPriorityScore, int limit);

    @HotRoute(value = "active-order-window",
            projection = OrderSummary.class,
            pageSize = 100, hotWindow = 1_000, memoryBudgetBytes = CacheMemoryBudget.MIB_16)
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "orderDate", operator = CachePredicate.Operator.GTE,
                            parameter = "cutoffEpochSeconds", group = 0),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.IN,
                            constants = {"NEW", "PAID", "PICKING", "OPEN", "PENDING"}, group = 1)
            },
            explicitDisjunction = true,
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            }
    )
    CursorPage<OrderSummary> activeWindow(long cutoffEpochSeconds, WindowRequest window);

    @SourceRoute(value = "customer-order-archive", projection = OrderSummary.class,
            maxRows = 500, timeoutSeconds = 15)
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "customerId", group = 0),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE,
                            constants = "DELETED", group = 0),
                    @CachePredicate(field = "orderDate", operator = CachePredicate.Operator.LT,
                            parameter = "beforeOrderDate", group = 0),
                    @CachePredicate(field = "customerId", group = 1),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE,
                            constants = "DELETED", group = 1),
                    @CachePredicate(field = "orderDate", parameter = "beforeOrderDate", group = 1),
                    @CachePredicate(field = "orderId", operator = CachePredicate.Operator.LT,
                            parameter = "beforeOrderId", group = 1)
            },
            explicitDisjunction = true,
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            }
    )
    CursorPage<OrderSummary> archive(
            long customerId,
            long beforeOrderDate,
            long beforeOrderId,
            WindowRequest window
    );

    @WarmRoute(value = "warm-customer-order-timeline", from = "customerTimeline", maxRows = 1_000)
    CacheWarmPlan warmCustomerTimeline(long customerId, int maxRows, CacheWarmTarget target);

    @WarmRoute(value = "warm-recent-high-value-orders", from = "recentHighValue",
            maxRows = 1_000, projectionsOnly = true)
    CacheWarmPlan warmRecentHighValue(BigDecimal minimumAmount, int maxRows);

    @WarmRoute(value = "warm-dashboard-highlighted-orders", from = "highlighted",
            maxRows = 1_000, projectionsOnly = true)
    CacheWarmPlan warmHighlighted(double minimumPriorityScore, int maxRows);

    @WarmRoute(value = "warm-active-order-window", from = "activeWindow", maxRows = 1_000)
    CacheWarmPlan warmActiveWindow(long cutoffEpochSeconds, int maxRows);
}
