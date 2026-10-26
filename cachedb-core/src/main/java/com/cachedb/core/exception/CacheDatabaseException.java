package com.reactor.cachedb.core.exception;

/** Base for explicit framework contract failures. */
public class CacheDatabaseException extends RuntimeException {
    public CacheDatabaseException(String message) { super(message); }
    public CacheDatabaseException(String message, Throwable cause) { super(message, cause); }
}
