package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.repository.SourceSqlQuery;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcSourceSqlRepositoryTest {

    @Test
    void executesBoundReadWithoutCacheAdmission() throws Exception {
        JdbcDataSource dataSource = dataSource();
        JdbcSourceSqlRepository<Row> repository = new JdbcSourceSqlRepository<>(dataSource, new RowCodec());

        List<Row> rows = repository.query(new SourceSqlQuery(
                "SELECT id, status FROM source_rows WHERE status = ? ORDER BY id",
                List.of("OPEN"),
                2,
                5
        ));

        assertEquals(List.of(new Row(1L, "OPEN"), new Row(3L, "OPEN")), rows);
    }

    @Test
    void rejectsMutationAndPlaceholderMismatchBeforeJdbcExecution() throws Exception {
        JdbcSourceSqlRepository<Row> repository = new JdbcSourceSqlRepository<>(dataSource(), new RowCodec());

        assertThrows(IllegalArgumentException.class, () -> repository.query(
                SourceSqlQuery.of("DELETE FROM source_rows", List.of(), 10)
        ));
        assertThrows(IllegalArgumentException.class, () -> repository.query(
                SourceSqlQuery.of("WITH changed AS (DELETE FROM source_rows RETURNING id) SELECT id FROM changed",
                        List.of(), 10)
        ));
        assertThrows(IllegalArgumentException.class, () -> repository.query(
                SourceSqlQuery.of("SELECT id INTO copied_rows FROM source_rows", List.of(), 10)
        ));
        assertThrows(IllegalArgumentException.class, () -> repository.query(
                SourceSqlQuery.of("SELECT id, status FROM source_rows WHERE status = ?", List.of(), 10)
        ));
    }

    @Test
    void failsWhenSqlReturnsMoreThanDeclaredBoundary() throws Exception {
        JdbcSourceSqlRepository<Row> repository = new JdbcSourceSqlRepository<>(dataSource(), new RowCodec());

        assertThrows(IllegalStateException.class, () -> repository.query(
                SourceSqlQuery.of("SELECT id, status FROM source_rows ORDER BY id", List.of(), 2)
        ));
    }

    private JdbcDataSource dataSource() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:source-sql-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE source_rows (id BIGINT PRIMARY KEY, status VARCHAR(32))");
            statement.execute("INSERT INTO source_rows VALUES (1, 'OPEN'), (2, 'CLOSED'), (3, 'OPEN')");
        }
        return dataSource;
    }

    private record Row(Long id, String status) {
    }

    private static final class RowCodec implements EntityCodec<Row> {
        @Override public String toRedisValue(Row entity) { throw new UnsupportedOperationException(); }
        @Override public Row fromRedisValue(String encoded) { throw new UnsupportedOperationException(); }
        @Override public Map<String, Object> toColumns(Row entity) { return Map.of(); }

        @Override
        public Row fromColumns(Map<String, Object> columns) {
            return new Row(((Number) columnValue(columns, "id")).longValue(),
                    String.valueOf(columnValue(columns, "status")));
        }
    }
}
