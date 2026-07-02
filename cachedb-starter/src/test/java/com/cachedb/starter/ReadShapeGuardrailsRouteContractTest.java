package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.guardrail.ReadShapeGuardrails;
import com.reactor.cachedb.core.route.RouteCacheContract;
import com.reactor.cachedb.core.route.RouteCacheStrictMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadShapeGuardrailsRouteContractTest {

    @Test
    void shouldFailFastWhenProjectionRequiredRouteFallsBackToEntity() {
        RouteCacheContract contract = RouteCacheContract.builder()
                .routeName("customer-orders")
                .projectionRequired(true)
                .projectionName("CustomerOrdersSummaryHot")
                .strictMode(RouteCacheStrictMode.FAIL_FAST)
                .build();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ReadShapeGuardrails.validateRouteContract(contract, "entity:OrderEntity")
        );

        assertTrue(exception.getMessage().contains("requires projection"));
        assertTrue(exception.getMessage().contains("entity:OrderEntity"));
    }

    @Test
    void shouldFailFastWhenStrictRouteReadExceedsColdReadLimit() {
        RouteCacheContract contract = RouteCacheContract.builder()
                .routeName("customer-orders")
                .maxColdReadSize(100)
                .strictMode(RouteCacheStrictMode.FAIL_FAST)
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ReadShapeGuardrails.validateRouteReadSize(contract, "Entity query", 250)
        );

        assertTrue(exception.getMessage().contains("maxColdReadSize is 100"));
    }
}
