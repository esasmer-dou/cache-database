package com.example.cachedb.sample.cacheapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.cachedb.sample.domain.SupplierOffer;
import com.example.cachedb.sample.domain.TenantStock;
import com.reactor.cachedb.core.api.AsyncEntityRepository;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.async.OffloadingAsyncCacheDbRepository;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.AsyncCacheDbRepository;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContexts;
import com.reactor.cachedb.reactor.ReactiveEntityRepositoryFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class CacheApiAsyncContextTest {
    @Test @SuppressWarnings("unchecked")
    void receiptAdapterCapturesAtCallTimeAndDoesNotLoseTheReceiptOrCallerContext() throws Exception {
        CacheDbRepository<SupplierOffer, Long> raw = mock(CacheDbRepository.class);
        var entity = new SupplierOffer(21L, "s", "ACTIVE", new BigDecimal("0.2500"), 3);
        var receipt = new WriteReceipt<>(entity, 21L, "sample-supplier-offers", OperationType.UPSERT, 9, Instant.now());
        when(raw.save(entity)).thenAnswer(call -> {
            assertTrue(Thread.currentThread().isVirtual());
            assertEquals("shop-a", CacheTenantContext.currentTenant().orElseThrow());
            assertEquals(8, CacheCausalContext.requiredVersion("other", "id"));
            return receipt;
        });
        try (var gate = new CacheAsyncExecutor(4, TraceContexts.threadLocal())) {
            var adapter = new OffloadingAsyncCacheDbRepository<>(raw, gate);
            CompletableFuture<WriteReceipt<SupplierOffer, Long>> pending;
            try (var tenant = CacheTenantContext.withTenant("shop-a");
                 var causal = CacheCausalContext.withMinVersion("other", "id", 8)) {
                pending = adapter.saveAsync(entity);
            }
            assertSame(receipt, pending.get(3, TimeUnit.SECONDS));
            assertTrue(CacheTenantContext.currentTenant().isEmpty());
            assertEquals("", CacheCausalContext.encodeToken());
            assertTrue(gate.awaitIdle(java.time.Duration.ofSeconds(2)));
        }
    }

    @Test @SuppressWarnings("unchecked")
    void reactivePublishersAreColdAndApplyEachAuthenticatedSubscriberContextNotAmbientAdmin() throws Exception {
        AsyncEntityRepository<TenantStock, Long> async = mock(AsyncEntityRepository.class);
        AsyncCacheDbRepository<SupplierOffer, Long> offers = mock(AsyncCacheDbRepository.class);
        var factory = new ReactiveEntityRepositoryFactory();
        var operations = new CacheApiOperations(offers, factory.from(async));
        when(async.findHotByIdAsync(1L)).thenAnswer(call -> {
            String tenant = CacheTenantContext.currentTenant().orElseThrow();
            assertFalse(CacheTenantContext.isAdministrative());
            assertEquals(7, CacheCausalContext.requiredVersion("stock", 1L));
            assertEquals(0, CacheCausalContext.requiredVersion("foreign", "id"));
            return CompletableFuture.completedFuture(HotLookup.hit(new TenantStock(1L, tenant, "W1", "AVAILABLE", 3)));
        });
        String token;
        try (var causal = CacheCausalContext.withMinVersion("stock", 1L, 7)) {
            token = CacheCausalContext.encodeToken();
        }
        var first = operations.stock(1, "shop-a", token);
        var second = operations.stock(1, "shop-b", token);
        verifyNoInteractions(async);
        try (var admin = CacheTenantContext.administrative();
             var foreign = CacheCausalContext.withMinVersion("foreign", "id", 99)) {
            assertEquals("shop-a", first.toFuture().get(2, TimeUnit.SECONDS).value().tenantId());
            assertEquals("shop-b", second.toFuture().get(2, TimeUnit.SECONDS).value().tenantId());
            assertEquals("shop-a", first.toFuture().get(2, TimeUnit.SECONDS).value().tenantId());
            assertTrue(CacheTenantContext.isAdministrative());
            assertTrue(CacheTenantContext.currentTenant().isEmpty());
            assertEquals(99, CacheCausalContext.requiredVersion("foreign", "id"));
        }
        verify(async, times(3)).findHotByIdAsync(1L);
        assertTrue(CacheTenantContext.currentTenant().isEmpty());
        assertEquals("", CacheCausalContext.encodeToken());
        assertThrows(IllegalArgumentException.class, () -> operations.stocks(java.util.Collections.nCopies(33, 1L), "shop-a", ""));
        assertThrows(IllegalArgumentException.class, () -> operations.stocks(List.of(0L), "shop-a", ""));
    }
}
