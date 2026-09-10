package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.readmodel.ProductAvailability;
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

@CacheRepository(entity = ProductEntity.class)
@CacheRepositoryDefaults(hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500, sourceTimeoutSeconds = 15)
public interface ProductRepository extends CacheDbRepository<ProductEntity, Long> {

    @CacheLookup
    HotLookup<ProductEntity> detail(Long productId);

    @HotRoute(value = "active-products-by-category",
            projection = ProductAvailability.class,
            pageSize = 100, hotWindow = 5_000, memoryBudgetBytes = CacheMemoryBudget.MIB_16,
            coverageScopeParameter = "category")
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "category"),
                    @CachePredicate(field = "activeStatus", constants = "ACTIVE")
            },
            orderBy = {
                    @CacheOrder(field = "sku"),
                    @CacheOrder(field = "productId")
            }
    )
    CursorPage<ProductAvailability> activeByCategory(String category, WindowRequest window);

    @HotRoute(value = "active-products",
            projection = ProductAvailability.class,
            pageSize = 100, hotWindow = 10_000, memoryBudgetBytes = CacheMemoryBudget.MIB_32)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "activeStatus", constants = "ACTIVE"),
            orderBy = {
                    @CacheOrder(field = "sku"),
                    @CacheOrder(field = "productId")
            }
    )
    CursorPage<ProductAvailability> active(WindowRequest window);

    @HotRoute(value = "low-stock-products",
            projection = ProductAvailability.class,
            pageSize = 100, hotWindow = 2_000, memoryBudgetBytes = CacheMemoryBudget.MIB_8)
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "stockStatus", constants = "LOW_STOCK"),
                    @CachePredicate(field = "activeStatus", constants = "ACTIVE")
            },
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "sku"),
                    @CacheOrder(field = "productId")
            }
    )
    HotWindow<ProductAvailability> lowStock(int limit);

    @SourceRoute(value = "inactive-product-archive", projection = ProductAvailability.class,
            maxRows = 500, timeoutSeconds = 15)
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "activeStatus", operator = CachePredicate.Operator.NE,
                            constants = "ACTIVE", group = 0),
                    @CachePredicate(field = "stockStatus", constants = "OUT_OF_STOCK", group = 1)
            },
            explicitDisjunction = true,
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "productId", direction = CacheOrder.Direction.DESC)
            }
    )
    CursorPage<ProductAvailability> inactiveArchive(WindowRequest window);

    @WarmRoute(value = "warm-active-products", from = "active", maxRows = 1_000)
    CacheWarmPlan warmActive(int maxRows, CacheWarmTarget target);

    @WarmRoute(value = "warm-category-products", from = "activeByCategory", maxRows = 1_000)
    CacheWarmPlan warmCategory(String category, int maxRows, CacheWarmTarget target);

    @WarmRoute(value = "warm-low-stock-products", from = "lowStock", maxRows = 1_000,
            projectionsOnly = true)
    CacheWarmPlan warmLowStock(int maxRows);
}
