package com.reactor.cachedb.core.retention;

import java.time.Instant;

public record RetentionSweepResult(int examined, int evicted, int indexCleaned, int deleteAccepted,
        int skipped, boolean exhausted, long cutoff, Instant finishedAt) {
    public RetentionSweepResult {
        if (examined < 0 || examined > 10_000 || evicted < 0 || indexCleaned < 0 || deleteAccepted < 0
                || skipped < 0 || skipped + indexCleaned != examined || evicted > indexCleaned
                || deleteAccepted > indexCleaned || finishedAt == null)
            throw new IllegalArgumentException("Invalid retention result");
    }
}
