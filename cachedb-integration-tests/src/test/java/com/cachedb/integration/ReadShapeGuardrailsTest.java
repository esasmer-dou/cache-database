package com.reactor.cachedb.integration;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.config.ReadShapeGuardrailConfig;
import com.reactor.cachedb.core.guardrail.ReadShapeGuardrails;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.route.RouteCacheContract;
import com.reactor.cachedb.core.route.RouteCacheStrictMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadShapeGuardrailsTest {

    @Test
    void shouldRejectPageRequestLargerThanConfiguredSafeWindow() {
        CachePolicy policy = CachePolicy.builder()
                .hotEntityLimit(50)
                .pageSize(20)
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ReadShapeGuardrails.validatePageRequest(
                        "OrderEntity",
                        new PageWindow(0, 21),
                        policy,
                        ReadShapeGuardrailConfig.defaults()
                )
        );

        assertEquals(
                "PageWindow for OrderEntity requested 21 rows but the configured safe limit is 20. "
                        + "Use a smaller page, a bounded projection window, or a ProjectionRepository read-model for large lists.",
                exception.getMessage()
        );
    }

    @Test
    void shouldKeepHotSetHeadroomWhenHotLimitIsLowerThanPageSize() {
        CachePolicy policy = CachePolicy.builder()
                .hotEntityLimit(10)
                .pageSize(100)
                .build();

        assertEquals(9, ReadShapeGuardrails.effectivePageRequestLimit(policy, ReadShapeGuardrailConfig.defaults()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadShapeGuardrails.validatePageRequest(
                        "CustomerEntity",
                        new PageWindow(0, 10),
                        policy,
                        ReadShapeGuardrailConfig.defaults()
                )
        );
    }

    @Test
    void shouldRejectAnyPositivePageWhenHotLimitCannotLeaveHeadroom() {
        CachePolicy policy = CachePolicy.builder()
                .hotEntityLimit(1)
                .pageSize(100)
                .build();

        assertEquals(0, ReadShapeGuardrails.effectivePageRequestLimit(policy, ReadShapeGuardrailConfig.defaults()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadShapeGuardrails.validatePageRequest(
                        "TinyEntity",
                        new PageWindow(0, 1),
                        policy,
                        ReadShapeGuardrailConfig.defaults()
                )
        );
    }

    @Test
    void shouldSkipOversizedLoadedPageCacheWhenConfiguredNotToReject() {
        CachePolicy policy = CachePolicy.builder()
                .hotEntityLimit(10)
                .pageSize(100)
                .build();
        ReadShapeGuardrailConfig config = ReadShapeGuardrailConfig.builder()
                .rejectLoadedPageOverLimit(false)
                .skipLoadedPageCacheOverLimit(true)
                .build();

        assertFalse(ReadShapeGuardrails.shouldCacheLoadedPage(10, policy, config));
    }

    @Test
    void shouldRejectLargeFullEntityQueryButAllowProjectionWindow() {
        CachePolicy policy = CachePolicy.builder()
                .hotEntityLimit(500)
                .pageSize(100)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> ReadShapeGuardrails.validateEntityQuery(
                        "OrderEntity",
                        QuerySpec.builder().limit(101).build(),
                        policy,
                        ReadShapeGuardrailConfig.defaults()
                )
        );

        assertDoesNotThrow(() -> ReadShapeGuardrails.validateProjectionQuery(
                "OrderSummaryProjection",
                QuerySpec.builder().limit(1_000).build(),
                ReadShapeGuardrailConfig.defaults()
        ));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadShapeGuardrails.validateProjectionQuery(
                        "OrderSummaryProjection",
                        QuerySpec.builder().limit(1_001).build(),
                        ReadShapeGuardrailConfig.defaults()
                )
        );
    }

    @Test
    void shouldFailFastWhenRouteRequiresProjectionButEntityFallbackResolved() {
        RouteCacheContract contract = RouteCacheContract.builder()
                .routeName("customer-orders-timeline")
                .entityName("OrderEntity")
                .projectionName("CustomerOrderSummaryHot")
                .projectionRequired(true)
                .strictMode(RouteCacheStrictMode.FAIL_FAST)
                .build();

        assertDoesNotThrow(() -> ReadShapeGuardrails.validateRouteContract(contract, "projection:CustomerOrderSummaryHot"));
        assertThrows(
                IllegalStateException.class,
                () -> ReadShapeGuardrails.validateRouteContract(contract, "entity:OrderEntity")
        );
    }
}
