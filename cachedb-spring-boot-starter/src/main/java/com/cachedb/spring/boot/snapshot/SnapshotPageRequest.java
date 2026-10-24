package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.repository.WindowRequest;
import java.util.Objects;

/** Inclusive sorted-score bounds. The existing window limit still bounds work and payload. */
public record SnapshotPageRequest(String partitionKey, double minScore, double maxScore, WindowRequest window) {
    public SnapshotPageRequest {
        SnapshotIndexSpec.partition(partitionKey);
        Objects.requireNonNull(window, "window");
        if (Double.isNaN(minScore) || Double.isNaN(maxScore) || minScore > maxScore)
            throw new IllegalArgumentException("Invalid snapshot score range");
    }
    public boolean unbounded() { return minScore == Double.NEGATIVE_INFINITY && maxScore == Double.POSITIVE_INFINITY; }
    String redisMinimum() { return minScore == Double.NEGATIVE_INFINITY ? "-inf" : minScore == Double.POSITIVE_INFINITY ? "+inf" : Double.toString(minScore); }
    String redisMaximum() { return maxScore == Double.POSITIVE_INFINITY ? "+inf" : maxScore == Double.NEGATIVE_INFINITY ? "-inf" : Double.toString(maxScore); }
}
