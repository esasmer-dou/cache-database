package com.example.cachedb.sample.web;

import java.time.Instant;

public record WriteAccepted<T>(
        String status,
        String operation,
        String entityType,
        Object entityId,
        T entity,
        long acceptedAt,
        String durability
) {

    private static final String DURABILITY_MESSAGE =
            "Redis accepted the command. SQL durability is asynchronous; monitor write-behind health before treating it as durable.";

    public static <T> WriteAccepted<T> of(String operation, String entityType, Object entityId, T entity) {
        return new WriteAccepted<>(
                "WRITE_BEHIND_ACCEPTED",
                operation,
                entityType,
                entityId,
                entity,
                Instant.now().getEpochSecond(),
                DURABILITY_MESSAGE
        );
    }
}
