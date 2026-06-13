package com.reactor.cachedb.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public interface JdbcOutboxDialect {
    String name();

    String createCheckpointTableSql(String checkpointTable);

    default String readCheckpointSql(String checkpointTable) {
        return "SELECT last_event_id FROM " + checkpointTable + " WHERE adapter_name = ?";
    }

    default String ensureCheckpointSql(String checkpointTable) {
        return "INSERT INTO " + checkpointTable
                + " (adapter_name, last_event_id, updated_at)"
                + " SELECT ?, 0, CURRENT_TIMESTAMP"
                + " WHERE NOT EXISTS (SELECT 1 FROM " + checkpointTable + " WHERE adapter_name = ?)";
    }

    default void bindEnsureCheckpoint(PreparedStatement statement, String adapterName) throws SQLException {
        statement.setString(1, adapterName);
        statement.setString(2, adapterName);
    }

    default String readCheckpointForUpdateSql(String checkpointTable) {
        return readCheckpointSql(checkpointTable) + " FOR UPDATE";
    }

    String readBatchSql(JdbcOutboxMapping mapping, int batchSize);

    void bindReadBatch(PreparedStatement statement, long checkpoint, int batchSize) throws SQLException;

    String writeCheckpointSql(String checkpointTable);

    void bindWriteCheckpoint(PreparedStatement statement, String adapterName, long lastEventId) throws SQLException;
}
