package com.reactor.cachedb.core.trace;

import java.util.Map;

/** No tracing SDK is required by core or storage modules. */
public interface TraceContextPropagator {
    Map<String, String> capture();
    Scope restore(Map<String, String> metadata);

    @FunctionalInterface
    interface Scope extends AutoCloseable { @Override void close(); }
}
