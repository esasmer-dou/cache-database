package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.readmodel.ProductAvailability;
import com.reactor.cachedb.annotations.CacheLookup;
import com.reactor.cachedb.annotations.CacheOrder;
import com.reactor.cachedb.annotations.CachePredicate;
import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.annotations.CacheRouteQuery;
import com.reactor.cachedb.annotations.HotRoute;
import com.reactor.cachedb.annotations.SourceRoute;
import com.reactor.cachedb.annotations.WarmRoute;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.repository.SourceWindow;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.starter.CacheWarmPlan;

@CacheRepository(entity = ProductEntity.class)
public interface ProductRepository extends CacheDbRepository<ProductEntity, Long> {

    @CacheLookup(idParameter = "productId")
    HotLookup<ProductEntity> detail(Long productId);

    @HotRoute(value = "active-products-by-category", projection = ProductAvailability.class,
            pageSize = 100, hotWindow = 5_000, memoryBudgetBytes = 16_777_216L,
            coverageScopeParameter = "category")
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "category", parameter = "category"),
                    @CachePredicate(field = "activeStatus", constants = "ACTIVE")
            },
            orderBy = {
                    @CacheOrder(field = "sku"),
                    @CacheOrder(field = "productId")
            },
            windowParameter = "window"
    )
    HotWindow<ProductAvailability> activeByCategory(String category, WindowRequest window);

    @HotRoute(value = "active-products", projection = ProductAvailability.class,
            pageSize = 100, hotWindow = 10_000, memoryBudgetBytes = 33_554_432L)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "activeStatus", constants = "ACTIVE"),
            orderBy = {
                    @CacheOrder(field = "sku"),
                    @CacheOrder(field = "productId")
            },
            windowParameter = "window"
    )
    HotWindow<ProductAvailability> active(WindowRequest window);

    @HotRoute(value = "low-stock-products", projection = ProductAvailability.class,
            pageSize = 100, hotWindow = 2_000, memoryBudgetBytes = 8_388_608L)
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "stockStatus", constants = "LOW_STOCK"),
                    @CachePredicate(field = "activeStatus", constants = "ACTIVE")
            },
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "sku"),
                    @CacheOrder(field = "productId")
            },
            limitParameter = "limit"
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
            orderBy = {
                    @CacheOrder(field = "updatedAt", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "productId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    SourceWindow<ProductAvailability> inactiveArchive(WindowRequest window);

    @WarmRoute(value = "warm-active-products-projection", from = "active", maxRows = 1_000,
            maxRowsParameter = "maxRows", projectionsOnly = true)
    CacheWarmPlan warmActiveProjection(int maxRows);

    @WarmRoute(value = "warm-active-products-entities", from = "active", maxRows = 1_000,
            maxRowsParameter = "maxRows")
    CacheWarmPlan warmActiveEntities(int maxRows);

    @WarmRoute(value = "warm-category-products-projection", from = "activeByCategory", maxRows = 1_000,
            maxRowsParameter = "maxRows", coverageScopeParameter = "category", projectionsOnly = true)
    CacheWarmPlan warmCategoryProjection(String category, int maxRows);

    @WarmRoute(value = "warm-category-products-entities", from = "activeByCategory", maxRows = 1_000,
            maxRowsParameter = "maxRows", coverageScopeParameter = "category")
    CacheWarmPlan warmCategoryEntities(String category, int maxRows);

    @WarmRoute(value = "warm-low-stock-products", from = "lowStock", maxRows = 1_000,
            maxRowsParameter = "maxRows", projectionsOnly = true)
    CacheWarmPlan warmLowStock(int maxRows);
}
