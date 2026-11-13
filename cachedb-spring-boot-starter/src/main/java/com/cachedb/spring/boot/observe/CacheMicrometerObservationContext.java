package com.reactor.cachedb.spring.boot.observe;

import com.reactor.cachedb.core.observe.*;
import io.micrometer.observation.Observation;
import java.util.List;

/** Handler contract only: putting origins here does NOT automatically export native links. */
public final class CacheMicrometerObservationContext extends Observation.Context {
    public static final String ORIGIN_LINKS_KEY = "cachedb.origin.links";
    private final CacheOperation operation;
    CacheMicrometerObservationContext(CacheOperation operation) {
        this.operation = operation;
        put(ORIGIN_LINKS_KEY, List.<CacheSpanLink>of());
    }
    public CacheOperation operation() { return operation; }
    public List<CacheSpanLink> originLinks() { return getRequired(ORIGIN_LINKS_KEY); }
    void originLinks(List<CacheSpanLink> links) { put(ORIGIN_LINKS_KEY, List.copyOf(links)); }
}
