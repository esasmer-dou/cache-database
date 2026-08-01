package com.reactor.cachedb.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;

public record CacheDistributedJobSnapshot(
        String jobId,
        String route,
        CacheDistributedJobState status,
        String ownerInstanceId,
        long submittedAtEpochMillis,
        Long startedAtEpochMillis,
        Long finishedAtEpochMillis,
        JsonNode result,
        JobError error
) {
    public record JobError(String type, String message) {
    }
}
