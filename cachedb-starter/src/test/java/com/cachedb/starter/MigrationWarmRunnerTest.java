package com.reactor.cachedb.starter;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationWarmRunnerTest {

    @Test
    void shouldWarmChildRowsAndReferencedRootsFromPostgres() throws SQLException {
        DataSource dataSource = newDataSource("warm-live");
        initializeSchema(dataSource);
        seedRows(dataSource, List.of(
                "INSERT INTO customer_account (customer_id, customer_type, entity_version, deleted_flag) VALUES (1, 'CORP', 11, 'N')",
                "INSERT INTO customer_account (customer_id, customer_type, entity_version, deleted_flag) VALUES (2, 'SMB', 12, 'N')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, entity_version, deleted_flag) VALUES (101, 1, TIMESTAMP '2026-04-01 10:00:00', 150.00, 5, 'N')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, entity_version, deleted_flag) VALUES (102, 2, TIMESTAMP '2026-04-02 10:00:00', 250.00, 6, 'N')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, entity_version, deleted_flag) VALUES (103, 404, TIMESTAMP '2026-04-03 10:00:00', 350.00, 7, 'N')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, entity_version, deleted_flag) VALUES (104, 1, TIMESTAMP '2026-04-04 10:00:00', 450.00, 8, 'Y')"
        ));

        RecordingHydrator rootHydrator = new RecordingHydrator(
                "CustomerEntity",
                "customer_account",
                "customer_id",
                "entity_version",
                "deleted_flag",
                "Y"
        );
        RecordingHydrator childHydrator = new RecordingHydrator(
                "CustomerOrderEntity",
                "customer_order",
                "order_id",
                "entity_version",
                "deleted_flag",
                "Y"
        );
        MigrationWarmRunner runner = new MigrationWarmRunner(
                dataSource,
                new RecordingHydratorFactory(rootHydrator, childHydrator)
        );

        MigrationWarmRunner.Result result = runner.execute(new MigrationWarmRunner.Request(
                new MigrationPlanner.Request(
                        "customer-orders",
                        "customer_account",
                        "customer_id",
                        "customer_order",
                        "order_id",
                        "customer_id",
                        "order_date",
                        "DESC",
                        2L,
                        4L,
                        3L,
                        4L,
                        2,
                        2,
                        true,
                        false,
                        false,
                        false,
                        true,
                        false,
                        true,
                        true,
                        true
                ),
                true,
                false,
                25,
                25,
                50
        ));

        assertFalse(result.dryRun());
        assertEquals("CustomerOrderEntity", result.childEntityName());
        assertEquals("CustomerEntity", result.rootEntityName());
        assertEquals(4L, result.childRowsRead());
        assertEquals(3L, result.childRowsHydrated());
        assertEquals(2L, result.rootRowsRead());
        assertEquals(2L, result.rootRowsHydrated());
        assertEquals(1L, result.skippedDeletedRows());
        assertEquals(3L, result.distinctReferencedRootIds());
        assertEquals(1L, result.missingReferencedRootIds());
        assertTrue(result.childWarmSql().contains("PARTITION BY customer_id"));
        assertTrue(result.rootWarmSql().contains(":referenced_root_ids"));
        assertEquals(List.of(101L, 102L, 103L), childHydrator.hydratedIds());
        assertEquals(List.of(5L, 6L, 7L), childHydrator.hydratedVersions());
        assertEquals(List.of(1L, 2L), rootHydrator.hydratedIds());
        assertEquals(List.of(11L, 12L), rootHydrator.hydratedVersions());
        assertTrue(childHydrator.forceImmediateProjectionRefreshFlags().stream().allMatch(Boolean::booleanValue));
        assertTrue(rootHydrator.forceImmediateProjectionRefreshFlags().stream().allMatch(Boolean::booleanValue));
        assertTrue(childHydrator.reindexQueryIndexesFlags().stream().noneMatch(Boolean::booleanValue));
        assertTrue(rootHydrator.reindexQueryIndexesFlags().stream().noneMatch(Boolean::booleanValue));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("projection payloads and projection query indexes were rebuilt inline")));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("Entity query indexes were deferred")));
    }

    @Test
    void shouldDryRunRankedWarmWithoutHydratingRedis() throws SQLException {
        DataSource dataSource = newDataSource("warm-dry-run");
        initializeSchema(dataSource);
        seedRows(dataSource, List.of(
                "INSERT INTO customer_account (customer_id, customer_type, entity_version, deleted_flag) VALUES (1, 'CORP', 21, 'N')",
                "INSERT INTO customer_account (customer_id, customer_type, entity_version, deleted_flag) VALUES (2, 'SMB', 22, 'N')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, entity_version, deleted_flag) VALUES (201, 1, TIMESTAMP '2026-04-01 10:00:00', 900.00, 31, 'N')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, entity_version, deleted_flag) VALUES (202, 2, TIMESTAMP '2026-04-02 10:00:00', 700.00, 32, 'N')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, entity_version, deleted_flag) VALUES (203, 1, TIMESTAMP '2026-04-03 10:00:00', 400.00, 33, 'N')"
        ));

        RecordingHydrator rootHydrator = new RecordingHydrator(
                "CustomerEntity",
                "customer_account",
                "customer_id",
                "entity_version",
                "deleted_flag",
                "Y"
        );
        RecordingHydrator childHydrator = new RecordingHydrator(
                "CustomerOrderEntity",
                "customer_order",
                "order_id",
                "entity_version",
                "deleted_flag",
                "Y"
        );
        MigrationWarmRunner runner = new MigrationWarmRunner(
                dataSource,
                new RecordingHydratorFactory(rootHydrator, childHydrator)
        );

        MigrationWarmRunner.Result result = runner.execute(new MigrationWarmRunner.Request(
                new MigrationPlanner.Request(
                        "top-customers",
                        "customer_account",
                        "customer_id",
                        "customer_order",
                        "order_id",
                        "customer_id",
                        "order_amount",
                        "DESC",
                        2L,
                        3L,
                        2L,
                        3L,
                        2,
                        2,
                        true,
                        false,
                        true,
                        true,
                        true,
                        false,
                        true,
                        true,
                        true
                ),
                true,
                true,
                25,
                25,
                50
        ));

        assertTrue(result.dryRun());
        assertTrue(result.plan().rankedProjectionRequired());
        assertFalse(result.childWarmSql().contains("PARTITION BY"));
        assertTrue(result.childWarmSql().contains("ORDER BY order_amount DESC"));
        assertEquals(3L, result.childRowsRead());
        assertEquals(3L, result.childRowsHydrated());
        assertEquals(2L, result.rootRowsRead());
        assertEquals(2L, result.rootRowsHydrated());
        assertTrue(childHydrator.hydratedRows().isEmpty());
        assertTrue(rootHydrator.hydratedRows().isEmpty());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("Dry run completed")));
    }

    private DataSource newDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void initializeSchema(DataSource dataSource) throws SQLException {
        seedRows(dataSource, List.of(
                """
                CREATE TABLE customer_account (
                    customer_id BIGINT PRIMARY KEY,
                    customer_type VARCHAR(32),
                    entity_version BIGINT,
                    deleted_flag VARCHAR(1)
                )
                """,
                """
                CREATE TABLE customer_order (
                    order_id BIGINT PRIMARY KEY,
                    customer_id BIGINT,
                    order_date TIMESTAMP,
                    order_amount DECIMAL(18, 2),
                    entity_version BIGINT,
                    deleted_flag VARCHAR(1)
                )
                """
        ));
    }

    private void seedRows(DataSource dataSource, List<String> statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static final class RecordingHydratorFactory implements MigrationWarmRunner.WarmEntityHydratorFactory {
        private final Map<String, RecordingHydrator> hydratorsByName = new LinkedHashMap<>();

        private RecordingHydratorFactory(RecordingHydrator... hydrators) {
            for (RecordingHydrator hydrator : hydrators) {
                hydratorsByName.put(hydrator.entityName().toLowerCase(), hydrator);
                hydratorsByName.put(hydrator.tableName().toLowerCase(), hydrator);
            }
        }

        @Override
        public Optional<MigrationWarmRunner.WarmEntityHydrator> resolve(String entityOrTableName) {
            if (entityOrTableName == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(hydratorsByName.get(entityOrTableName.toLowerCase()));
        }
    }

    private static final class RecordingHydrator implements MigrationWarmRunner.WarmEntityHydrator {
        private final String entityName;
        private final String tableName;
        private final String idColumn;
        private final String versionColumn;
        private final String deletedColumn;
        private final String deletedMarkerValue;
        private final List<HydratedRow> hydratedRows = new ArrayList<>();
        private final List<Boolean> forceImmediateProjectionRefreshFlags = new ArrayList<>();
        private final List<Boolean> reindexQueryIndexesFlags = new ArrayList<>();

        private RecordingHydrator(
                String entityName,
                String tableName,
                String idColumn,
                String versionColumn,
                String deletedColumn,
                String deletedMarkerValue
        ) {
            this.entityName = entityName;
            this.tableName = tableName;
            this.idColumn = idColumn;
            this.versionColumn = versionColumn;
            this.deletedColumn = deletedColumn;
            this.deletedMarkerValue = deletedMarkerValue;
        }

        @Override
        public String entityName() {
            return entityName;
        }

        @Override
        public String tableName() {
            return tableName;
        }

        @Override
        public String idColumn() {
            return idColumn;
        }

        @Override
        public String versionColumn() {
            return versionColumn;
        }

        @Override
        public String deletedColumn() {
            return deletedColumn;
        }

        @Override
        public String deletedMarkerValue() {
            return deletedMarkerValue;
        }

        @Override
        public void hydrate(Map<String, Object> row, long version) {
            hydratedRows.add(new HydratedRow(version, new LinkedHashMap<>(row), Instant.now()));
        }

        @Override
        public void hydrateBatch(
                List<Map<String, Object>> rows,
                List<Long> versions,
                boolean forceImmediateProjectionRefresh,
                boolean reindexQueryIndexes
        ) {
            forceImmediateProjectionRefreshFlags.add(forceImmediateProjectionRefresh);
            reindexQueryIndexesFlags.add(reindexQueryIndexes);
            MigrationWarmRunner.WarmEntityHydrator.super.hydrateBatch(
                    rows,
                    versions,
                    forceImmediateProjectionRefresh,
                    reindexQueryIndexes
            );
        }

        private List<Long> hydratedIds() {
            return hydratedRows.stream()
                    .map(row -> valueOf(row.row(), idColumn))
                    .map(value -> ((Number) value).longValue())
                    .toList();
        }

        private List<Long> hydratedVersions() {
            return hydratedRows.stream()
                    .map(HydratedRow::version)
                    .toList();
        }

        private List<HydratedRow> hydratedRows() {
            return hydratedRows;
        }

        private List<Boolean> forceImmediateProjectionRefreshFlags() {
            return forceImmediateProjectionRefreshFlags;
        }

        private List<Boolean> reindexQueryIndexesFlags() {
            return reindexQueryIndexesFlags;
        }

        private Object valueOf(Map<String, Object> row, String column) {
            if (row.containsKey(column)) {
                return row.get(column);
            }
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    private record HydratedRow(long version, Map<String, Object> row, Instant hydratedAt) {
    }
}
