package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;

/**
 * Administrative, distributed read/fill/runtime closure for one namespace + tenant.
 * Implementations must block every registered read, L1, delayed fill, projection refresh and producer
 * route, drain already admitted work, invalidate L1 epochs, and attest the complete runtime manifest.
 * Pub/Sub delivery, a local counter, an empty runtime list, or a Redis CLOSED flag alone is not proof.
 * Unknown runtime/history/route ownership is INCOMPLETE, never an inferred acknowledgement.
 */
public interface TenantReadFence {
    enum State { DRAINING, CLOSED, INCOMPLETE }
    record Evidence(State state, String checkpoint, String token, int registered, int acknowledged) {
        public Evidence {
            if (state == null || checkpoint == null || !checkpoint.matches("[A-Za-z0-9:._-]{0,1024}")
                    || registered < 0 || registered > 10000 || acknowledged < 0 || acknowledged > registered)
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
            if (state == State.CLOSED) {
                ProtectionKey.token(token);
                if (registered == 0 || acknowledged != registered)
                    throw new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);
            } else if (token != null) ProtectionKey.token(token);
        }
    }

    /** Bounded live round; checkpoint is opaque, non-PII and restartable under a replacement lease. */
    Evidence closeAndDrain(Lease lease, long generation, String manifestToken,
            String checkpoint, int maxOperations, int maxMillis);

    /**
     * Live fail-closed check, called before AND after every native destructive/verification step.
     * Evidence must bind key/op/generation/manifest AND current lease owner/epoch and Redis run IDs.
     * Throw WRITE_FENCED, LEASE_LOST, or INCOMPLETE_INVENTORY on lost/unknown authority.
     * No SQL call is permitted on foreground reads or writes; closure is an administrative operation.
     */
    void requireClosed(Lease lease, long generation, String manifestToken, Evidence evidence);

    /** Exact approved runtime coverage, also usable after OPEN. No membership inference or auto-enrollment. */
    default void requireActivationCoverage(ProtectionKey key, String manifestToken) {
        throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
    }

    /** Releases administrative resources only. MUST NOT open any gate or restore invalidated L1. */
    void release(Lease lease);
}
