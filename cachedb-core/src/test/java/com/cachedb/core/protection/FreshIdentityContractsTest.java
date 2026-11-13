package com.reactor.cachedb.core.protection;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.FreshIdentityRange.Request;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FreshIdentityContractsTest {
    private final Binding binding = new Binding(String.class, "fresh-orders",
            new SourcePlan("orders", "tenant_id", "id"), ProtectionKey.hash("layout"), 128, 16, 4096, true);
    private FreshIdentityDomain domain(long first, long last) {
        return FreshIdentityDomain.exclusivelyReserved(binding, UUID.randomUUID(), first, last,
                ProtectionKey.hash("independently-reserved-domain"));
    }

    @Test void explicitDomainIsNamespaceGlobalNotTenantVersionState() {
        var domain = domain(100, 199);
        assertEquals(binding.key("a").scopeToken(), domain.scopeToken());
        assertEquals(binding.key("b").scopeToken(), domain.scopeToken());
        assertEquals(binding.fingerprint(), domain.bindingFingerprint());
        var request = new Request(domain, new Generation(binding.key("a"), 2), 16, UUID.randomUUID(), 7);
        var range = new FreshIdentityRange(request, 3, 8, 100, 115);
        assertSame(request, range.request());
        assertEquals(16, range.last() - range.first() + 1);
        assertFalse(range.toString().contains(domain.scopeToken()));
        assertFalse(request.toString().contains(request.requestId().toString()));
        assertEquals("FreshIdentityDomain[redacted]", domain.toString());
    }

    @Test void malformedOversizedAndUnattestedDomainsAreRejected() {
        assertThrows(ProtectionException.class, () -> domain(0, 100));
        assertThrows(ProtectionException.class, () -> domain(100, 99));
        assertThrows(ProtectionException.class, () -> domain(1, FreshIdentityDomain.MAX_IDENTITY + 1));
        assertThrows(ProtectionException.class, () -> new FreshIdentityDomain("x".repeat(65),
                binding.fingerprint(), UUID.randomUUID(), 1, 10, ProtectionKey.hash("exclusive")));
        assertThrows(ProtectionException.class, () -> new FreshIdentityDomain(binding.token(),
                "A".repeat(64), UUID.randomUUID(), 1, 10, ProtectionKey.hash("exclusive")));
        assertThrows(ProtectionException.class, () -> new FreshIdentityDomain(binding.token(),
                binding.fingerprint(), UUID.randomUUID(), 1, 10, null));
        assertThrows(NullPointerException.class, () -> FreshIdentityDomain.exclusivelyReserved(binding,
                null, 1, 10, ProtectionKey.hash("exclusive")));
        var unprotected = new Binding(String.class, binding.scope(), binding.source(), binding.layoutToken(),
                128, 16, 4096, false);
        assertThrows(ProtectionException.class, () -> FreshIdentityDomain.exclusivelyReserved(unprotected,
                UUID.randomUUID(), 1, 10, ProtectionKey.hash("exclusive")));
    }

    @Test void immutableRequestsAndReceiptsBoundScopeSizeEpochAndArithmetic() {
        var domain = domain(100, 199);
        var generation = new Generation(binding.key("a"), 1);
        assertThrows(ProtectionException.class, () -> new Request(domain, generation, 0, UUID.randomUUID(), 0));
        assertThrows(ProtectionException.class, () -> new Request(domain, generation,
                FreshIdentityRange.MAX_RANGE + 1, UUID.randomUUID(), 0));
        assertThrows(ProtectionException.class, () -> new Request(domain, generation, 1, UUID.randomUUID(), -1));
        assertThrows(ProtectionException.class, () -> new Request(domain, generation, 1, UUID.randomUUID(), Long.MAX_VALUE));
        assertThrows(ProtectionException.class, () -> new Request(domain, generation, 1, UUID.randomUUID(), FreshIdentityRange.MAX_EPOCH));
        assertThrows(ProtectionException.class, () -> new Request(domain,
                new Generation(generation.key(), FreshIdentityRange.MAX_EPOCH + 1), 1, UUID.randomUUID(), 0));
        assertThrows(ProtectionException.class, () -> new Request(domain,
                new Generation(ProtectionKey.of("foreign", "a"), 1), 1, UUID.randomUUID(), 0));
        var request = new Request(domain, generation, 2, UUID.randomUUID(), 0);
        assertThrows(ProtectionException.class, () -> new FreshIdentityRange(request, 0, 1, 100, 101));
        assertThrows(ProtectionException.class, () -> new FreshIdentityRange(request, FreshIdentityRange.MAX_EPOCH + 1, 1, 100, 101));
        assertThrows(ProtectionException.class, () -> new FreshIdentityRange(request, 1, 2, 100, 101));
        assertThrows(ProtectionException.class, () -> new FreshIdentityRange(request, 1, 1, 99, 100));
        assertThrows(ProtectionException.class, () -> new FreshIdentityRange(request, 1, 1, 199, 200));
        assertThrows(ProtectionException.class, () -> new FreshIdentityRange(request, 1, 1, 100, 102));
        var edge = new Request(domain(FreshIdentityDomain.MAX_IDENTITY, FreshIdentityDomain.MAX_IDENTITY),
                generation, 1, UUID.randomUUID(), FreshIdentityRange.MAX_EPOCH - 1);
        assertEquals(FreshIdentityRange.MAX_EPOCH, new FreshIdentityRange(edge, FreshIdentityRange.MAX_EPOCH, FreshIdentityRange.MAX_EPOCH,
                FreshIdentityDomain.MAX_IDENTITY, FreshIdentityDomain.MAX_IDENTITY).reservationEpoch());
    }
}
