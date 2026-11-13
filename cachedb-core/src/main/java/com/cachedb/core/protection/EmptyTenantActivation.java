package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.util.Objects;
import java.util.UUID;

/** Explicit EMPTY activation. Neither purge completion nor lease acquisition is an activation request. */
public final class EmptyTenantActivation {
    private EmptyTenantActivation() {}
    public enum Mode { EMPTY }
    public enum Phase { PREPARED, SQL_OPEN, ACTIVE }

    public record Request(UUID activationId, Mode mode, PurgeOptions purge,
            String purgeCertificate, String manifestToken) {
        public Request {
            Objects.requireNonNull(activationId); Objects.requireNonNull(mode); Objects.requireNonNull(purge);
            ProtectionKey.token(purgeCertificate); ProtectionKey.token(manifestToken);
            if (!purge.includeSource()) throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        }
        @Override public String toString() { return "EmptyActivationRequest[redacted]"; }
    }

    public record Identity(ProtectionKey key, UUID operationId, UUID activationId, long fromGeneration,
            long toGeneration, String purgeCertificate, String manifestToken, String inventoryToken,
            String operatorToken) {
        public Identity {
            Objects.requireNonNull(key); Objects.requireNonNull(operationId); Objects.requireNonNull(activationId);
            if (fromGeneration < 1 || fromGeneration == Long.MAX_VALUE || toGeneration != fromGeneration + 1)
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
            ProtectionKey.token(purgeCertificate); ProtectionKey.token(manifestToken);
            ProtectionKey.token(inventoryToken); ProtectionKey.token(operatorToken);
        }
        public String token() {
            return ProtectionKey.hash("empty-v1:" + key.scopeToken() + ":" + key.tenantToken() + ":"
                    + operationId + ":" + activationId + ":" + fromGeneration + ":" + toGeneration + ":"
                    + purgeCertificate + ":" + manifestToken + ":" + inventoryToken + ":" + operatorToken);
        }
    }

    /** The reservation is frozen at SQL_OPEN. Recovery must not reserve again or reset its counter. */
    public record Checkpoint(Identity identity, Phase phase, long attemptEpoch, UUID attemptOwner,
            int queueCursor, TenantVersionRange reservation) {
        public Checkpoint {
            Objects.requireNonNull(identity); Objects.requireNonNull(phase); Objects.requireNonNull(attemptOwner);
            if (attemptEpoch < 1 || queueCursor < 0 || queueCursor > 128
                    || (phase == Phase.PREPARED) != (reservation == null)) invalid();
            if (reservation != null && (!reservation.key().equals(identity.key())
                    || reservation.generation() != identity.toGeneration()
                    || !reservation.inventory().equals(identity.inventoryToken()))) invalid();
        }
        public void requireLease(Lease lease) {
            if (!identity.key().equals(lease.key()) || !identity.operationId().equals(lease.operationId())
                    || attemptEpoch != lease.epoch() || !attemptOwner.equals(lease.owner()))
                throw new ProtectionException(ProtectionException.Code.LEASE_LOST);
        }
        public String receiptToken() {
            if (reservation == null) invalid();
            return ProtectionKey.hash(identity.token() + ":" + reservation.reservationEpoch() + ":"
                    + reservation.request() + ":" + reservation.first() + ":" + reservation.last()
                    + ":" + reservation.runId());
        }
        @Override public String toString() { return "EmptyActivationCheckpoint[phase=" + phase + "]"; }
    }

    /** Native observation, not an assertion that SQL and Redis share a transaction. */
    public record Receipt(String identityToken, String receiptToken, String runId, long epoch, UUID owner) {
        public Receipt {
            ProtectionKey.token(identityToken); ProtectionKey.token(receiptToken); Objects.requireNonNull(owner);
            if (runId == null || !runId.matches("[0-9a-f]{40}") || epoch < 1) invalid();
        }
        public void require(Checkpoint checkpoint) {
            if (!identityToken.equals(checkpoint.identity().token())
                    || !receiptToken.equals(checkpoint.receiptToken())
                    || !runId.equals(checkpoint.reservation().runId())
                    || epoch != checkpoint.attemptEpoch() || !owner.equals(checkpoint.attemptOwner())) invalid();
        }
    }
    private static void invalid() { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
}
