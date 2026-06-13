package com.reactor.cachedb.postgres;

import com.reactor.cachedb.jdbc.JdbcOutboxDialect;
import com.reactor.cachedb.jdbc.JdbcOutboxMapping;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class PostgresOutboxDialect implements JdbcOutboxDialect {
    @Override
    public String name() {
        return "postgres";
    }

    @Override
    public String createCheckpointTableSql(String checkpointTable) {
        return "CREATE TABLE IF NOT EXISTS " + checkpointTable + " ("
                + "adapter_name TEXT PRIMARY KEY,"
                + "last_event_id BIGINT NOT NULL,"
                + "updated_at TIMESTAMPTZ NOT NULL"
                + ")";
    }

    @Override
    public String readBatchSql(JdbcOutboxMapping mapping, int batchSize) {
        return "SELECT "
                + mapping.idColumn() + ", "
                + mapping.entityColumn() + ", "
                + mapping.entityIdColumn() + ", "
                + mapping.typeColumn() + ", "
                + mapping.payloadColumn() + ", "
                + mapping.versionColumn() + ", "
                + mapping.occurredAtColumn() + ", "
                + mapping.sourceColumn()
                + " FROM " + mapping.outboxTable()
                + " WHERE " + mapping.idColumn() + " > ?"
                + " ORDER BY " + mapping.idColumn() + " ASC"
                + " LIMIT ?";
    }

    @Override
    public void bindReadBatch(PreparedStatement statement, long checkpoint, int batchSize) throws SQLException {
        statement.setLong(1, checkpoint);
        statement.setInt(2, Math.max(1, batchSize));
    }

    @Override
    public String writeCheckpointSql(String checkpointTable) {
        return "INSERT INTO " + checkpointTable
                + " (adapter_name, last_event_id, updated_at) VALUES (?, ?, now())"
                + " ON CONFLICT (adapter_name) DO UPDATE SET"
                + " last_event_id = EXCLUDED.last_event_id,"
                + " updated_at = EXCLUDED.updated_at";
    }

    @Override
    public void bindWriteCheckpoint(PreparedStatement statement, String adapterName, long lastEventId) throws SQLException {
        statement.setString(1, adapterName);
        statement.setLong(2, lastEventId);
    }
}
