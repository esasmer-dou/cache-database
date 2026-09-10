package com.example.cachedb.sample.web;

import com.reactor.cachedb.core.model.WriteReceipt;

public record WriteAccepted<T>(
        String status,
        String operation,
        String entityType,
        Object entityId,
        T entity,
        long version,
        long acceptedAt,
        String durability
) {

    private static final String DURABILITY_MESSAGE =
            "Redis accepted the command. SQL durability is asynchronous; monitor write-behind health before treating it as durable.";

    public static <T, ID> WriteAccepted<T> from(
            String operation,
            String entityType,
            WriteReceipt<T, ID> receipt
    ) {
        return new WriteAccepted<>(
                "WRITE_BEHIND_ACCEPTED",
                operation,
                entityType,
                receipt.id(),
                receipt.entity(),
                receipt.version(),
                receipt.acceptedAt().getEpochSecond(),
                DURABILITY_MESSAGE
        );
    }
}
