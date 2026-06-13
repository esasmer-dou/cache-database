package com.reactor.cachedb.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public interface JdbcOutboxDialect {
    String name();

    String createCheckpointTableSql(String checkpointTable);

    default String readCheckpointSql(String checkpointTable) {
        return "SELECT last_event_id FROM " + checkpointTable + " WHERE adapter_name = ?";
    }

    String readBatchSql(JdbcOutboxMapping mapping, int batchSize);

    void bindReadBatch(PreparedStatement statement, long checkpoint, int batchSize) throws SQLException;

    String writeCheckpointSql(String checkpointTable);

    void bindWriteCheckpoint(PreparedStatement statement, String adapterName, long lastEventId) throws SQLException;
}
