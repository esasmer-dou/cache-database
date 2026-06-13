package com.reactor.cachedb.mssql;

import com.reactor.cachedb.jdbc.JdbcOutboxMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MssqlOutboxDialectTest {

    private final MssqlOutboxDialect dialect = new MssqlOutboxDialect();

    @Test
    void shouldUseTopWindowForOutboxPolling() {
        String sql = dialect.readBatchSql(JdbcOutboxMapping.defaults(), 25);

        assertTrue(sql.startsWith("SELECT TOP (25)"));
        assertTrue(sql.contains("WHERE id > ?"));
        assertTrue(sql.contains("ORDER BY id ASC"));
    }

    @Test
    void shouldCheckpointWithoutMerge() {
        String sql = dialect.writeCheckpointSql("cachedb_outbox_adapter_checkpoint");

        assertTrue(sql.startsWith("UPDATE cachedb_outbox_adapter_checkpoint WITH (UPDLOCK, HOLDLOCK)"));
        assertTrue(sql.contains("IF @@ROWCOUNT = 0 INSERT INTO cachedb_outbox_adapter_checkpoint"));
    }
}
