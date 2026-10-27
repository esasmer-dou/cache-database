package com.example.cachedb.sample.controls;

import static org.junit.jupiter.api.Assertions.*;
import com.example.cachedb.sample.domain.*;
import com.example.cachedb.sample.repository.SupplierOfferRepositoryCacheDbImplementation;
import com.reactor.cachedb.core.testing.InMemoryCacheDatabase;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ControlsContractTest {
    @Test void generatedContractsKeepOptionalAndUnsafeControlsExplicit() {
        assertEquals(2, SupplierOfferCacheBinding.METADATA.nearCachePolicy().softTtlSeconds());
        assertEquals(10, SupplierOfferCacheBinding.METADATA.nearCachePolicy().ttlSeconds());
        assertTrue(SupplierOfferCacheBinding.METADATA.independentWriteReordering());
        assertFalse(TenantStockCacheBinding.METADATA.independentWriteReordering());
        assertEquals(1, SupplierOfferCacheBinding.METADATA.compositeExactIndexes().size());
        assertNotNull(TenantStockCacheBinding.METADATA.tenantFieldName());
    }
    @Test void generatedFacetsAndAggregateUseExactDecimals() {
        var memory = new InMemoryCacheDatabase();
        var offers = new SupplierOfferRepositoryCacheDbImplementation(memory);
        offers.save(new SupplierOffer(1L, "acme", "ACTIVE", new BigDecimal("0.1000"), 2));
        offers.save(new SupplierOffer(2L, "acme", "ACTIVE", new BigDecimal("0.2000"), 3));
        assertEquals(Map.of("ACTIVE", 2L), offers.countsBySupplier("acme"));
        assertEquals(0, new BigDecimal("0.3000").compareTo(offers.priceTotalsBySupplier("acme").get("ACTIVE")));
        assertEquals(0, new BigDecimal("0.3000").compareTo(offers.sumPriceBySupplierAndStatus("acme", "ACTIVE")));
    }
    @Test void authenticationChoosesTenantOnTheServerNotFromARequestTenantId() {
        var access = new ControlsAccess("supplier", "token-a", "token-b");
        assertEquals("shop-a", access.tenant("Bearer token-a"));
        assertEquals("shop-b", access.tenant("Bearer token-b"));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> access.tenant(null)).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> access.tenant("Bearer shop-a")).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.supplier("Bearer token-a")).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.tenant("Bearer supplier")).getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> new ControlsAccess("same", "same", ""));
    }
    @Test void missingCredentialsFailClosedAndWarmInputsAreBounded() {
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> new ControlsAccess("", "", "").supplier("Bearer anything")).getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> new OfferWarmCommand("acme", 101));
        assertThrows(IllegalArgumentException.class, () -> new OfferWarmCommand("", 1));
        assertThrows(IllegalArgumentException.class, () -> new SupplierOffer(1L, "acme", "ACTIVE",
                new BigDecimal("1e2147483647"), 1));
        assertThrows(IllegalArgumentException.class, () -> new TenantStock(1L, "shop-a", "W1", "AVAILABLE", -1));
    }
}
