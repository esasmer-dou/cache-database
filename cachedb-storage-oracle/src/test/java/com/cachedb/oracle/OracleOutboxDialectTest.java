package com.reactor.cachedb.oracle;

import com.reactor.cachedb.jdbc.JdbcOutboxMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleOutboxDialectTest {
    @Test
    void shouldUseBoundedFetchAndCheckpointRowLock() {
        OracleOutboxDialect dialect = new OracleOutboxDialect(7);

        assertTrue(dialect.readBatchSql(JdbcOutboxMapping.defaults(), 25).endsWith("FETCH FIRST 25 ROWS ONLY"));
        assertTrue(dialect.readCheckpointForUpdateSql("cachedb_checkpoint").endsWith("FOR UPDATE WAIT 7"));
    }

    @Test
    void shouldUseIdempotentMergeForCheckpointState() {
        OracleOutboxDialect dialect = new OracleOutboxDialect();

        assertTrue(dialect.ensureCheckpointSql("cachedb_checkpoint").startsWith("BEGIN MERGE INTO cachedb_checkpoint"));
        assertTrue(dialect.ensureCheckpointSql("cachedb_checkpoint").contains("DUP_VAL_ON_INDEX"));
        assertTrue(dialect.writeCheckpointSql("cachedb_checkpoint").contains("WHEN MATCHED THEN UPDATE"));
        assertTrue(dialect.createCheckpointTableSql("cachedb_checkpoint").contains("SQLCODE != -955"));
    }

    @Test
    void shouldRejectUnboundedLockWait() {
        assertThrows(IllegalArgumentException.class, () -> new OracleOutboxDialect(301));
    }
}
