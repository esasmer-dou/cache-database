package com.reactor.cachedb.core.config;

public record WriteRetryPolicy(
        int maxRetries,
        long backoffMillis
) {
    public static WriteRetryPolicy of(int maxRetries, long backoffMillis) {
        return new WriteRetryPolicy(maxRetries, backoffMillis);
    }
}
