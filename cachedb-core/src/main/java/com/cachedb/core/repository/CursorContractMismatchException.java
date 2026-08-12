package com.reactor.cachedb.core.repository;

/** Raised before query execution when a cursor belongs to another route contract. */
public final class CursorContractMismatchException extends IllegalArgumentException {
    private final String expectedFingerprint;
    private final String actualFingerprint;

    public CursorContractMismatchException(String expectedFingerprint, String actualFingerprint) {
        super("CacheDB cursor does not belong to the requested route, scope, or sort contract");
        this.expectedFingerprint = expectedFingerprint;
        this.actualFingerprint = actualFingerprint;
    }

    public String expectedFingerprint() {
        return expectedFingerprint;
    }

    public String actualFingerprint() {
        return actualFingerprint;
    }
}
