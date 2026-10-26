package com.reactor.cachedb.core.exception;

/** Intentionally contains no foreign tenant identity or row contents. */
public final class CacheTenantAccessDeniedException extends CacheDatabaseException {
    public CacheTenantAccessDeniedException(String entityName) { super("Tenant access denied for " + entityName); }
}
