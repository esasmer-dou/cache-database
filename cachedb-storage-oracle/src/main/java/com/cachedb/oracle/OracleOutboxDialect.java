package com.reactor.cachedb.oracle;

import com.reactor.cachedb.jdbc.JdbcOutboxDialect;
import com.reactor.cachedb.jdbc.JdbcOutboxMapping;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class OracleOutboxDialect implements JdbcOutboxDialect {
    private final int lockWaitSeconds;

    public OracleOutboxDialect() {
        this(5);
    }

    public OracleOutboxDialect(int lockWaitSeconds) {
        if (lockWaitSeconds < 0 || lockWaitSeconds > 300) {
            throw new IllegalArgumentException("lockWaitSeconds must be between 0 and 300");
        }
        this.lockWaitSeconds = lockWaitSeconds;
    }

    @Override
    public String name() {
        return "oracle";
    }

    @Override
    public String createCheckpointTableSql(String checkpointTable) {
        String ddl = "CREATE TABLE " + checkpointTable + " ("
                + "adapter_name VARCHAR2(200 CHAR) NOT NULL PRIMARY KEY,"
                + "last_event_id NUMBER(19) NOT NULL,"
                + "updated_at TIMESTAMP WITH TIME ZONE NOT NULL"
                + ")";
        return "BEGIN EXECUTE IMMEDIATE '" + ddl + "'; "
                + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;";
    }

    @Override
    public String ensureCheckpointSql(String checkpointTable) {
        return "BEGIN MERGE INTO " + checkpointTable + " target"
                + " USING (SELECT ? adapter_name FROM dual) source"
                + " ON (target.adapter_name = source.adapter_name)"
                + " WHEN NOT MATCHED THEN INSERT (adapter_name, last_event_id, updated_at)"
                + " VALUES (source.adapter_name, 0, SYSTIMESTAMP);"
                + " EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL; END;";
    }

    @Override
    public void bindEnsureCheckpoint(PreparedStatement statement, String adapterName) throws SQLException {
        statement.setString(1, adapterName);
    }

    @Override
    public String readCheckpointForUpdateSql(String checkpointTable) {
        return "SELECT last_event_id FROM " + checkpointTable
                + " WHERE adapter_name = ? FOR UPDATE WAIT " + lockWaitSeconds;
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
                + " FETCH FIRST " + Math.max(1, batchSize) + " ROWS ONLY";
    }

    @Override
    public void bindReadBatch(PreparedStatement statement, long checkpoint, int batchSize) throws SQLException {
        statement.setLong(1, checkpoint);
    }

    @Override
    public String writeCheckpointSql(String checkpointTable) {
        return "MERGE INTO " + checkpointTable + " target"
                + " USING (SELECT ? adapter_name, ? last_event_id FROM dual) source"
                + " ON (target.adapter_name = source.adapter_name)"
                + " WHEN MATCHED THEN UPDATE SET target.last_event_id = source.last_event_id,"
                + " target.updated_at = SYSTIMESTAMP"
                + " WHEN NOT MATCHED THEN INSERT (adapter_name, last_event_id, updated_at)"
                + " VALUES (source.adapter_name, source.last_event_id, SYSTIMESTAMP)";
    }

    @Override
    public void bindWriteCheckpoint(PreparedStatement statement, String adapterName, long lastEventId)
            throws SQLException {
        statement.setString(1, adapterName);
        statement.setLong(2, lastEventId);
    }
}
