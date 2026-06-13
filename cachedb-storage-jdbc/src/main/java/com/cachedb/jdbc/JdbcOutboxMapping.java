package com.reactor.cachedb.jdbc;

public record JdbcOutboxMapping(
        String outboxTable,
        String checkpointTable,
        String idColumn,
        String entityColumn,
        String entityIdColumn,
        String typeColumn,
        String payloadColumn,
        String versionColumn,
        String occurredAtColumn,
        String sourceColumn
) {
    public JdbcOutboxMapping {
        outboxTable = requireIdentifier(outboxTable, "outboxTable", true);
        checkpointTable = requireIdentifier(checkpointTable, "checkpointTable", true);
        idColumn = requireIdentifier(idColumn, "idColumn", false);
        entityColumn = requireIdentifier(entityColumn, "entityColumn", false);
        entityIdColumn = requireIdentifier(entityIdColumn, "entityIdColumn", false);
        typeColumn = requireIdentifier(typeColumn, "typeColumn", false);
        payloadColumn = requireIdentifier(payloadColumn, "payloadColumn", false);
        versionColumn = requireIdentifier(versionColumn, "versionColumn", false);
        occurredAtColumn = requireIdentifier(occurredAtColumn, "occurredAtColumn", false);
        sourceColumn = requireIdentifier(sourceColumn, "sourceColumn", false);
    }

    public static JdbcOutboxMapping defaults() {
        return new JdbcOutboxMapping(
                "cachedb_outbox",
                "cachedb_outbox_adapter_checkpoint",
                "id",
                "entity_name",
                "entity_id",
                "event_type",
                "payload_json",
                "entity_version",
                "occurred_at",
                "event_source"
        );
    }

    private static String requireIdentifier(String value, String name, boolean allowQualified) {
        String normalized = requireText(value, name);
        String pattern = allowQualified
                ? "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"
                : "[A-Za-z_][A-Za-z0-9_]*";
        if (!normalized.matches(pattern)) {
            throw new IllegalArgumentException(name + " contains an unsafe SQL identifier: " + normalized);
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
