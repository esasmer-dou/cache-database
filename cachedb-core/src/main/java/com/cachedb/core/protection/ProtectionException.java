package com.reactor.cachedb.core.protection;

/** Public errors contain neither raw tenant/IDs nor vendor SQL text/bind values. */
public final class ProtectionException extends RuntimeException {
    public enum Code {
        ADMIN_REQUIRED, CONFIRMATION_REQUIRED, INVALID_REQUEST, UNWIRED_PROTOCOL, BUSY,
        LEASE_LOST, OPERATION_CONFLICT, INVALID_PROGRESS, STORAGE_UNAVAILABLE,
        INDEX_REQUIRED, WRITE_FENCED, GENERATION_MISMATCH, INCOMPLETE_INVENTORY, AUDIT_FAILED,
        MIGRATION_REQUIRED, VERSION_LIMIT, VERSION_EXHAUSTED, RESERVATION_CONFLICT, RECOVERY_REQUIRED
    }
    private final Code code;
    public ProtectionException(Code code) {
        super("CacheDB data protection: " + code);
        this.code = code;
    }
    public Code code() { return code; }
}
