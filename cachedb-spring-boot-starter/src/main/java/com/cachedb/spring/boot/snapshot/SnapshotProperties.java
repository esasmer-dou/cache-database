package com.reactor.cachedb.spring.boot.snapshot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** Names are plan IDs; unknown options fail binding instead of being silently ignored. */
@ConfigurationProperties(prefix = "cachedb.snapshots", ignoreUnknownFields = false)
public record SnapshotProperties(Map<String, SnapshotSettings> jobs) {
    public SnapshotProperties {
        jobs = jobs == null ? Map.of() : Map.copyOf(jobs);
    }
}
