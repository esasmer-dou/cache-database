package com.reactor.cachedb.core.observe;

/** Supplies the current native span's W3C header, or null. Never reconstruct headers from MDC. */
@FunctionalInterface
public interface CacheTraceparentSupplier extends java.util.function.Supplier<String> {
    CacheTraceparentSupplier NONE = () -> null;
}
