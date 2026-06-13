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
        String lockName = "cachedb-outbox-checkpoint-" + checkpointTable;
        return "DECLARE @cachedb_lock_result INT; "
                + "EXEC @cachedb_lock_result = sp_getapplock"
                + " @Resource = N'" + lockName + "',"
                + " @LockMode = 'Exclusive',"
                + " @LockOwner = 'Session',"
                + " @LockTimeout = 10000; "
                + "IF @cachedb_lock_result < 0 THROW 51000, 'Could not acquire CacheDB checkpoint schema lock', 1; "
                + "IF OBJECT_ID(N'" + checkpointTable + "', N'U') IS NULL "
                + "CREATE TABLE " + checkpointTable + " ("
                + "adapter_name NVARCHAR(200) NOT NULL PRIMARY KEY,"
                + "last_event_id BIGINT NOT NULL,"
                + "updated_at DATETIMEOFFSET NOT NULL"
                + "); "
                + "EXEC sp_releaseapplock"
                + " @Resource = N'" + lockName + "',"
                + " @LockOwner = 'Session'";
    }

    @Override
    public String ensureCheckpointSql(String checkpointTable) {
        return "BEGIN TRY "
                + "IF NOT EXISTS (SELECT 1 FROM " + checkpointTable + " WITH (UPDLOCK, HOLDLOCK) WHERE adapter_name = ?) "
                + "INSERT INTO " + checkpointTable
                + " (adapter_name, last_event_id, updated_at) VALUES (?, 0, SYSDATETIMEOFFSET()); "
                + "END TRY "
                + "BEGIN CATCH "
                + "IF ERROR_NUMBER() NOT IN (2601, 2627) THROW; "
                + "END CATCH";
    }

    @Override
    public void bindEnsureCheckpoint(PreparedStatement statement, String adapterName) throws SQLException {
        statement.setString(1, adapterName);
        statement.setString(2, adapterName);
    }

    @Override
    public String readCheckpointForUpdateSql(String checkpointTable) {
        return "SELECT last_event_id FROM " + checkpointTable
                + " WITH (UPDLOCK, HOLDLOCK)"
                + " WHERE adapter_name = ?";
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
