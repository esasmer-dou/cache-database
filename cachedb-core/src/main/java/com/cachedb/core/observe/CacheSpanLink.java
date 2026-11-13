package com.reactor.cachedb.core.observe;

/** W3C v00 traceparent; never a metric dimension or a remote-parent instruction. */
public record CacheSpanLink(String traceparent) {
    public CacheSpanLink {
        if (!CacheTraceparent.valid(traceparent)) throw new IllegalArgumentException("Invalid W3C traceparent");
    }
    public String traceId() { return traceparent.substring(3, 35); }
    public String spanId() { return traceparent.substring(36, 52); }
    public boolean sampled() { return (Character.digit(traceparent.charAt(54), 16) & 1) != 0; }
}
