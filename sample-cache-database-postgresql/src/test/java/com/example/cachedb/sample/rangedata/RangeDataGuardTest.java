package com.example.cachedb.sample.rangedata;

import static org.junit.jupiter.api.Assertions.*;
import com.example.cachedb.sample.controls.ControlsAccess;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RangeDataGuardTest {
    private final RangeDataGuard guard = new RangeDataGuard(new ControlsAccess("supplier-token", "tenant-a-token", "tenant-b-token"));

    @Test void verifiedCredentialsSelectTenantAndScopeAlwaysRestores() {
        assertEquals("shop-a", guard.call("Bearer tenant-a-token", "", tenant -> {
            assertEquals(tenant, CacheTenantContext.currentTenant().orElseThrow()); return tenant;
        }));
        assertTrue(CacheTenantContext.currentTenant().isEmpty());
        assertThrows(IllegalStateException.class, () -> guard.call("Bearer tenant-b-token", "", tenant -> {
            throw new IllegalStateException("failure");
        }));
        assertTrue(CacheTenantContext.currentTenant().isEmpty());
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> guard.call(null, "", tenant -> tenant)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> guard.call("Bearer supplier-token", "", tenant -> tenant)).getStatusCode().value());
    }

    @Test void headerAndAmbientCausalRequirementsFailBeforeRepositoryAccess() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> guard.call("Bearer tenant-a-token", "anything", tenant -> calls.incrementAndGet())).getStatusCode().value());
        try (var causal = CacheCausalContext.withMinVersion("sample-range-rows", "shop-a:r:a", 7)) {
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> guard.call("Bearer tenant-a-token", "", tenant -> calls.incrementAndGet())).getStatusCode().value());
        }
        assertEquals(0, calls.get());
    }

    @Test void ambientTenantOrAdministrativePrivilegeCannotOverrideVerifiedIdentity() {
        try (var tenant = CacheTenantContext.withTenant("shop-b")) {
            assertThrows(ResponseStatusException.class, () -> guard.call("Bearer tenant-a-token", "", value -> value));
            assertEquals("shop-b", CacheTenantContext.currentTenant().orElseThrow());
        }
        try (var admin = CacheTenantContext.administrative()) {
            assertThrows(ResponseStatusException.class, () -> guard.call("Bearer tenant-a-token", "", value -> value));
        }
    }

    @Test void explicitEmptyCausalScopeIsAcceptedAndOuterRequirementsRestore() {
        try (var outer = CacheCausalContext.withMinVersion("sample-range-rows", "shop-a:r:a", 7)) {
            assertTrue(CacheCausalContext.hasRequirements());
            try (var empty = CacheCausalContext.empty()) {
                assertFalse(CacheCausalContext.hasRequirements());
                assertEquals("shop-a", guard.call("Bearer tenant-a-token", "", tenant -> tenant));
            }
            assertTrue(CacheCausalContext.hasRequirements());
        }
        assertFalse(CacheCausalContext.hasRequirements());
    }

    @Test void boundsAndExactSignedLongValuesAreNotRounded() {
        assertEquals(Long.MIN_VALUE, RangeDataGuard.rank("-9223372036854775808"));
        assertEquals(Long.MAX_VALUE, RangeDataGuard.rank("9223372036854775807"));
        assertEquals(9007199254740993L, RangeDataGuard.rank("9007199254740993"));
        assertThrows(IllegalArgumentException.class, () -> RangeDataGuard.rank("9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> RangeDataGuard.rank("1e3"));
        assertThrows(IllegalArgumentException.class, () -> RangeDataGuard.limit(33));
        assertThrows(IllegalArgumentException.class, () -> RangeDataGuard.run("x".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> RangeDataGuard.id("shop-a", "r", "../x"));
        assertThrows(IllegalArgumentException.class, () -> RangeDataGuard.cursor("a".repeat(8193)));
        assertThrows(IllegalArgumentException.class,
                () -> new RangeDataRow("shop-b:r:a", "shop-a", "r", "OPEN", 1L));
    }
}
