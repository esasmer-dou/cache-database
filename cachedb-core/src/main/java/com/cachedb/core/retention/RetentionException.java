package com.reactor.cachedb.core.retention;

public final class RetentionException extends RuntimeException {
    public enum Code { ADMIN_REQUIRED, BUSY, LEASE_LOST, INPUT_LIMIT, UNSUPPORTED_LAYOUT, STORAGE_FAILURE, INVALID_REQUEST }
    private final Code code;
    public RetentionException(Code code) { super(code.name()); this.code = java.util.Objects.requireNonNull(code); }
    public Code code() { return code; }
}
