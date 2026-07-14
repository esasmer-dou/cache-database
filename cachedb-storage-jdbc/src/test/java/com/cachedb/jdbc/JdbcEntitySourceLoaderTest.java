package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcEntitySourceLoaderTest {

    @Test
    void shouldLoadByIdAndIgnoreDeletedRows() throws SQLException {
        JdbcEntitySourceLoader<TestEntity, Long> loader = newLoader(seedDataSource());

        Optional<TestEntity> active = loader.load(2L);
        Optional<TestEntity> deleted = loader.load(4L);

        assertTrue(active.isPresent());
        assertEquals("PENDING", active.get().status());
        assertTrue(deleted.isEmpty());
    }

    @Test
    void shouldCarryDatabaseVersionWithLoadedEntity() throws SQLException {
        JdbcEntitySourceLoader<TestEntity, Long> loader = newLoader(seedDataSource());

        var loaded = loader.loadVersionedById(2L).orElseThrow();

        assertEquals(2L, loaded.entity().id());
        assertEquals(20L, loaded.version());
    }

    @Test
    void shouldLoadBoundedSortedQuery() throws SQLException {
        JdbcEntitySourceLoader<TestEntity, Long> loader = newLoader(seedDataSource());

        List<TestEntity> rows = loader.load(QuerySpec.where(QueryFilter.eq("status", "OPEN"))
                .orderBy(QuerySort.desc("created_at"))
                .limitTo(2));

        assertEquals(List.of(3L, 1L), rows.stream().map(TestEntity::id).toList());
    }

    @Test
    void shouldNotAppendDuplicateStableIdOrderingWhenIdAlreadySorted() throws SQLException {
        JdbcEntitySourceLoader<TestEntity, Long> loader = newLoader(seedDataSource());

        assertEquals(
                " ORDER BY created_at DESC, id DESC",
                loader.renderOrderBy(List.of(QuerySort.desc("created_at"), QuerySort.desc("id")))
        );
        assertEquals(
                " ORDER BY created_at DESC, id ASC",
                loader.renderOrderBy(List.of(QuerySort.desc("created_at")))
        );
    }

    @Test
    void shouldLoadPageWithStableIdOrdering() throws SQLException {
        JdbcEntitySourceLoader<TestEntity, Long> loader = newLoader(seedDataSource());

        List<TestEntity> rows = loader.load(new PageWindow(1, 2));

        assertEquals(List.of(3L, 5L), rows.stream().map(TestEntity::id).toList());
    }

    @Test
    void shouldRejectUnsafeOrUnknownQueryColumns() throws SQLException {
        JdbcEntitySourceLoader<TestEntity, Long> loader = newLoader(seedDataSource());

        assertThrows(IllegalArgumentException.class, () -> loader.load(
                QuerySpec.where(QueryFilter.eq("status;drop", "OPEN")).limitTo(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> loader.load(
                QuerySpec.where(QueryFilter.eq("missing_column", "OPEN")).limitTo(1)
        ));
    }

    @Test
    void shouldRejectQueriesOverConfiguredMaxRows() throws SQLException {
        JdbcEntitySourceLoader<TestEntity, Long> loader = new JdbcEntitySourceLoader<>(
                seedDataSource(),
                new TestMetadata(),
                new TestCodec(),
                2
        );

        assertThrows(IllegalArgumentException.class, () -> loader.load(QuerySpec.builder().limit(3).build()));
    }

    private JdbcEntitySourceLoader<TestEntity, Long> newLoader(DataSource dataSource) {
        return new JdbcEntitySourceLoader<>(
                dataSource,
                new TestMetadata(),
                new TestCodec(),
                10
        );
    }

    private DataSource seedDataSource() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:jdbc-loader-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE test_entities (
                        id BIGINT PRIMARY KEY,
                        status VARCHAR(32),
                        created_at BIGINT,
                        entity_version BIGINT NOT NULL,
                        deleted_flag VARCHAR(8)
                    )
                    """);
            statement.execute("INSERT INTO test_entities(id, status, created_at, entity_version, deleted_flag) VALUES (1, 'OPEN', 100, 10, NULL)");
            statement.execute("INSERT INTO test_entities(id, status, created_at, entity_version, deleted_flag) VALUES (2, 'PENDING', 200, 20, NULL)");
            statement.execute("INSERT INTO test_entities(id, status, created_at, entity_version, deleted_flag) VALUES (3, 'OPEN', 300, 30, NULL)");
            statement.execute("INSERT INTO test_entities(id, status, created_at, entity_version, deleted_flag) VALUES (4, 'OPEN', 400, 40, 'true')");
            statement.execute("INSERT INTO test_entities(id, status, created_at, entity_version, deleted_flag) VALUES (5, 'CLOSED', 500, 50, NULL)");
        }
        return dataSource;
    }

    private record TestEntity(long id, String status, long createdAt) {
    }

    private static final class TestMetadata implements EntityMetadata<TestEntity, Long> {
        @Override
        public String entityName() {
            return "TestEntity";
        }

        @Override
        public String tableName() {
            return "test_entities";
        }

        @Override
        public String redisNamespace() {
            return "test-entities";
        }

        @Override
        public String idColumn() {
            return "id";
        }

        @Override
        public String deletedColumn() {
            return "deleted_flag";
        }

        @Override
        public Class<TestEntity> entityType() {
            return TestEntity.class;
        }

        @Override
        public Function<TestEntity, Long> idAccessor() {
            return TestEntity::id;
        }

        @Override
        public List<String> columns() {
            return List.of("id", "status", "created_at");
        }

        @Override
        public List<RelationDefinition> relations() {
            return List.of();
        }
    }

    private static final class TestCodec implements EntityCodec<TestEntity> {
        @Override
        public String toRedisValue(TestEntity entity) {
            return entity.id() + "|" + entity.status() + "|" + entity.createdAt();
        }

        @Override
        public TestEntity fromRedisValue(String encoded) {
            String[] parts = encoded.split("\\|", -1);
            return new TestEntity(Long.parseLong(parts[0]), parts[1], Long.parseLong(parts[2]));
        }

        @Override
        public Map<String, Object> toColumns(TestEntity entity) {
            return Map.of("id", entity.id(), "status", entity.status(), "created_at", entity.createdAt());
        }

        @Override
        public TestEntity fromColumns(Map<String, Object> columns) {
            return new TestEntity(
                    ((Number) columns.get("id")).longValue(),
                    String.valueOf(columns.get("status")),
                    ((Number) columns.get("created_at")).longValue()
            );
        }
    }
}
