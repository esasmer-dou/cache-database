package com.reactor.cachedb.core.protection;

import java.util.Objects;
import java.util.UUID;

/**
 * Confirmation is request-local; operator is an opaque SHA-256 token, not a name/email.
 * includeSource=false forbids SQL DELETE and SQL-delete enqueue: source/history stay retained.
 * includeSource=true permits only explicit bounded JDBC erasure after durable guard closure.
 * Retention DELETE is a different API and follows ordinary SQL write-behind.
 */
public final class PurgeOptions {
    private final UUID operationId;
    private final PurgeScope scope;
    private final String confirmation;
    private final String operatorToken;
    private final boolean includeSource;
    private final int maxRows;
    private final int leaseSeconds;
    private final int maxCallMillis;

    public PurgeOptions(UUID operationId, String confirmation, String operatorToken,
            boolean includeSource, int maxRows, int leaseSeconds, int maxCallMillis, PurgeScope scope) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        if (scope != PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA)
            throw new ProtectionException(ProtectionException.Code.CONFIRMATION_REQUIRED);
        this.scope = scope;
        ProtectionKey.checkTenant(confirmation);
        this.confirmation = confirmation;
        this.operatorToken = ProtectionKey.token(operatorToken);
        if (maxRows < 1 || maxRows > 10_000 || leaseSeconds < 15 || leaseSeconds > 120
                || maxCallMillis < 1 || maxCallMillis > 30_000)
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        this.includeSource = includeSource;
        this.maxRows = maxRows;
        this.leaseSeconds = leaseSeconds;
        this.maxCallMillis = maxCallMillis;
    }
    public PurgeScope scope() { return scope; }
    public UUID operationId() { return operationId; }
    public String confirmation() { return confirmation; }
    public String operatorToken() { return operatorToken; }
    public boolean includeSource() { return includeSource; }
    public int maxRows() { return maxRows; }
    public int leaseSeconds() { return leaseSeconds; }
    public int maxCallMillis() { return maxCallMillis; }
    @Override public String toString() { return "PurgeOptions[redacted]"; }
}
