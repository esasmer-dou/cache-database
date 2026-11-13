package com.reactor.cachedb.core.protection;

import java.time.Instant;
import java.util.UUID;

/** Progress is not completion; COMPLETE retains CLOSED admission. No raw tenant/ID/confirmation/cursor. */
public record PurgeReceipt(UUID operationId, ProtectionKey key, long closedGeneration, long newGeneration,
        TenantPurgeProtocol.Stage stage, long redisRows, long redisKeys, long sourceRows,
        boolean sourceIncluded, Instant startedAt, Instant completedAt, String operatorToken, int requiredBatchRows) {
    public PurgeReceipt {
        java.util.Objects.requireNonNull(operationId); java.util.Objects.requireNonNull(key);
        java.util.Objects.requireNonNull(stage); java.util.Objects.requireNonNull(startedAt);
        ProtectionKey.token(operatorToken);
        if (closedGeneration < 1 || newGeneration < 0 || redisRows < 0 || redisKeys < 0 || sourceRows < 0
                || requiredBatchRows < 0 || requiredBatchRows > 128 || !sourceIncluded && sourceRows != 0
                || !sourceIncluded && (stage == TenantPurgeProtocol.Stage.SOURCE
                    || stage == TenantPurgeProtocol.Stage.REOPENING || stage == TenantPurgeProtocol.Stage.REENABLED))
            throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);
    }
    public boolean requiresMigration() { return stage == TenantPurgeProtocol.Stage.REQUIRES_MIGRATION; }
    public boolean complete() { return stage == TenantPurgeProtocol.Stage.COMPLETE || stage == TenantPurgeProtocol.Stage.REENABLED; }
    public PurgeScope scope() { return PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA; }
    /** Deliberately retained durable identity/security metadata may contain canonical entity IDs. */
    public boolean retainsSecurityMetadata() { return true; }
    @Override public String toString() {
        return "PurgeReceipt[operation=" + operationId + ",stage=" + stage + ",closedGeneration=" + closedGeneration + "]";
    }
}
