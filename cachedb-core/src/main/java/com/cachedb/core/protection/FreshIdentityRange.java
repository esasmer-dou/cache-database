package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable administrative reservation, NOT a native Redis grant or entity/version/owner authority.
 * Unused identities are burned. Purging a tenant never releases any part of this interval.
 */
public record FreshIdentityRange(Request request, long controlEpoch, long reservationEpoch,
        long first, long last) {
    public static final int MAX_RANGE = 1024;
    public static final long MAX_EPOCH = 9_007_199_254_740_991L;

    /**
     * The immutable request identity INCLUDES expectedReservationEpoch. Never rebase a retry onto
     * a newer epoch. Only the latest receipt is retained; a retired request fails closed, not replayed.
     */
    public record Request(FreshIdentityDomain domain, Generation generation, int count,
            UUID requestId, long expectedReservationEpoch) {
        public Request {
            Objects.requireNonNull(domain);
            Objects.requireNonNull(generation);
            Objects.requireNonNull(requestId);
            if (!domain.scopeToken().equals(generation.key().scopeToken()) || count < 1
                    || count > MAX_RANGE || generation.value() > MAX_EPOCH || expectedReservationEpoch < 0
                    || expectedReservationEpoch >= MAX_EPOCH)
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        }
        @Override public String toString() { return "FreshIdentityRequest[redacted]"; }
    }

    public FreshIdentityRange {
        Objects.requireNonNull(request);
        if (controlEpoch < 1 || controlEpoch > MAX_EPOCH
                || reservationEpoch != request.expectedReservationEpoch() + 1
                || first < request.domain().first() || last < first || last > request.domain().last()
                || last - first != request.count() - 1L)
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
    }

    @Override public String toString() { return "FreshIdentityRange[redacted]"; }
}
