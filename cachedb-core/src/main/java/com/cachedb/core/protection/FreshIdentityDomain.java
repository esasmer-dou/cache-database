package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit administrative attestation that a numeric interval was exclusively reserved for this
 * namespace, across ALL tenants and historical/external writers. Not inferred from an empty store
 * or a source MAX query. This value neither creates owners nor authorizes ordinary writes.
 */
public record FreshIdentityDomain(String scopeToken, String bindingFingerprint, UUID authorityId,
        long first, long last, String exclusiveReservationToken) {
    public static final long MAX_IDENTITY = 9_007_199_254_740_991L;

    public FreshIdentityDomain {
        ProtectionKey.token(scopeToken);
        ProtectionKey.token(bindingFingerprint);
        ProtectionKey.token(exclusiveReservationToken);
        Objects.requireNonNull(authorityId);
        if (first < 1 || last < first || last > MAX_IDENTITY)
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
    }

    /** Caller must independently establish exclusivity; this method checks no external history. */
    public static FreshIdentityDomain exclusivelyReserved(Binding binding, UUID authorityId,
            long first, long last, String exclusiveReservationToken) {
        Objects.requireNonNull(binding);
        if (!binding.tenantPurgeEnabled())
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        return new FreshIdentityDomain(binding.token(), binding.fingerprint(), authorityId,
                first, last, exclusiveReservationToken);
    }

    @Override public String toString() { return "FreshIdentityDomain[redacted]"; }
}
