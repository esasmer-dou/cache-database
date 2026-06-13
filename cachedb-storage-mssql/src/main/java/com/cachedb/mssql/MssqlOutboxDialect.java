package com.reactor.cachedb.mssql;

import com.reactor.cachedb.jdbc.JdbcOutboxDialect;
import com.reactor.cachedb.jdbc.JdbcOutboxMapping;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class MssqlOutboxDialect implements JdbcOutboxDialect {
    @Override
    public String name() {
        return "mssql";
    }

    @Override
    public String createCheckpointTableSql(String checkpointTable) {
        return "IF OBJECT_ID(N'" + checkpointTable + "', N'U') IS NULL "
                + "CREATE TABLE " + checkpointTable + " ("
                + "adapter_name NVARCHAR(200) NOT NULL PRIMARY KEY,"
                + "last_event_id BIGINT NOT NULL,"
                + "updated_at DATETIMEOFFSET NOT NULL"
                + ")";
    }

    @Override
    public String readBatchSql(JdbcOutboxMapping mapping, int batchSize) {
        return "SELECT TOP (" + Math.max(1, batchSize) + ") "
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
                + " ORDER BY " + mapping.idColumn() + " ASC";
    }

    @Override
    public void bindReadBatch(PreparedStatement statement, long checkpoint, int batchSize) throws SQLException {
        statement.setLong(1, checkpoint);
    }

    @Override
    public String writeCheckpointSql(String checkpointTable) {
        return "UPDATE " + checkpointTable + " WITH (UPDLOCK, HOLDLOCK)"
                + " SET last_event_id = ?, updated_at = SYSDATETIMEOFFSET()"
                + " WHERE adapter_name = ?; "
                + "IF @@ROWCOUNT = 0 "
                + "INSERT INTO " + checkpointTable
                + " (adapter_name, last_event_id, updated_at) VALUES (?, ?, SYSDATETIMEOFFSET())";
    }

    @Override
    public void bindWriteCheckpoint(PreparedStatement statement, String adapterName, long lastEventId) throws SQLException {
        statement.setLong(1, lastEventId);
        statement.setString(2, adapterName);
        statement.setString(3, adapterName);
        statement.setLong(4, lastEventId);
    }
}
