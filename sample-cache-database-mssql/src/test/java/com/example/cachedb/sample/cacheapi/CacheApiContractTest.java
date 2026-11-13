package com.example.cachedb.sample.cacheapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.cachedb.sample.controls.ControlsAccess;
import com.example.cachedb.sample.domain.SupplierOffer;
import com.reactor.cachedb.core.async.CacheAsyncRejectedException;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CacheApiContractTest {
    final ControlsAccess access = new ControlsAccess("supplier", "token-a", "token-b");
    final MethodCacheService cache = mock(MethodCacheService.class);
    final CacheApiOperations operations = mock(CacheApiOperations.class);
    final CacheApiController controller = new CacheApiController(access, cache, operations);

    @Test void authenticatedTenantOverridesAmbientScopeAndRestoresItWithoutAcceptingCallerTenantIds() {
        when(cache.quote("SKU", 2)).thenAnswer(call -> {
            assertEquals("shop-a", CacheTenantContext.currentTenant().orElseThrow());
            assertFalse(CacheTenantContext.isAdministrative());
            return "value-a";
        });
        try (var ambient = CacheTenantContext.withTenant("other")) {
            assertEquals("shop-a", controller.quote("Bearer token-a", "SKU", 2, "").tenant());
            assertEquals("other", CacheTenantContext.currentTenant().orElseThrow());
        }
        assertTrue(CacheTenantContext.currentTenant().isEmpty());
        verify(cache).quote("SKU", 2);
    }

    @Test void missingWrongRoleOrCausalRequestsFailBeforeMethodSourceOrAsyncDispatch() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new CacheApiErrors()).build();
        mvc.perform(get("/api/examples/v019/cache/quotes/SKU")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/examples/v019/cache/quotes/SKU").header("Authorization", "Bearer supplier"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/examples/v019/cache/quotes/SKU").header("Authorization", "Bearer token-a")
                .header("X-CacheDB-Causal", "nonempty")).andExpect(status().isConflict());
        try (var causal = CacheCausalContext.withMinVersion("entity", "id", 42)) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> controller.refresh("Bearer token-a", "SKU", 0, ""));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> controller.clear("Bearer token-a", ""));
        }
        verifyNoInteractions(cache, operations);
    }

    @Test void invalidBodiesIdsListsAndKeysAreRejectedBeforeAnyWork() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new CacheApiErrors()).build();
        mvc.perform(put("/api/examples/v019/async/offers/1").header("Authorization", "Bearer supplier")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplier\":\"s\",\"status\":\"ACTIVE\",\"unitPrice\":1,\"availableUnits\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/examples/v019/async/offers/0").header("Authorization", "Bearer supplier"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/examples/v019/cache/quotes/SKU").param("branch", "1000")
                .header("Authorization", "Bearer token-a")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/examples/v019/cache/quotes/lowercase")
                .header("Authorization", "Bearer token-a")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/examples/v019/reactive/stock").param("ids", "0")
                .header("Authorization", "Bearer token-a")).andExpect(status().isBadRequest());
        verifyNoInteractions(cache, operations);
    }

    @Test void writesKeepTheActualReceiptAndHotMissNeverBecomesAnImplicitSourceLookup() throws Exception {
        var entity = new SupplierOffer(71L, "supplier", "ACTIVE", new BigDecimal("1.2500"), 8);
        var receipt = new WriteReceipt<>(entity, 71L, "sample-supplier-offers",
                OperationType.UPSERT, 19, Instant.now());
        when(operations.save(entity)).thenReturn(CompletableFuture.completedFuture(receipt));
        var input = new CacheApiController.OfferInput("supplier", "ACTIVE", new BigDecimal("1.2500"), 8);
        var response = controller.save("Bearer supplier", 71, input, "").get(2, TimeUnit.SECONDS);
        assertEquals(202, response.getStatusCode().value());
        assertSame(entity, response.getBody().entity());
        assertEquals(19, response.getBody().version());
        assertTrue(response.getBody().redisAccepted());
        assertFalse(response.getBody().sqlDurabilityConfirmed());
        assertEquals(response.getBody().causalToken(), response.getHeaders().getFirst("X-CacheDB-Causal"));
        try (var causal = CacheCausalContext.withToken(response.getBody().causalToken())) {
            assertEquals(19, CacheCausalContext.requiredVersion("sample-supplier-offers", 71L));
        }
        when(operations.hot(72)).thenReturn(CompletableFuture.completedFuture(HotLookup.notCached()));
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> controller.hot("Bearer supplier", 72, "").get(2, TimeUnit.SECONDS));
        assertInstanceOf(org.springframework.web.server.ResponseStatusException.class, failure.getCause());
        verify(operations, never()).source(anyLong());
        assertEquals("", CacheCausalContext.encodeToken());
    }

    @Test void saturationIs429AndResponseTimeoutDoesNotClaimRollback() throws Exception {
        when(operations.hot(1)).thenReturn(CompletableFuture.failedFuture(
                new CacheAsyncRejectedException(CacheAsyncRejectedException.Reason.SATURATED)));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new CacheApiErrors()).build();
        var pending = mvc.perform(get("/api/examples/v019/async/offers/1")
                .header("Authorization", "Bearer supplier")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));
        var timeout = new CacheApiErrors().failure(new java.util.concurrent.TimeoutException());
        assertEquals(504, timeout.getStatusCode().value());
        assertTrue(timeout.getBody().getDetail().contains("may still complete"));
    }
}
