package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.queue.SchemaMigrationPlan;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import oracle.jdbc.pool.OracleDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OracleMigrationPlannerTest {
    private static final String JDBC_URL = System.getProperty(
            "cachedb.it.oracle.url",
            "jdbc:oracle:thin:@//127.0.0.1:15211/FREEPDB1"
    );
    private static final String JDBC_USER = System.getProperty("cachedb.it.oracle.user", "cachedb");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.it.oracle.password", "CacheDbOracle123");

    @BeforeEach
    void setUp() throws Exception {
        assumeReachable();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            dropView(statement, "cachedb_oracle_order_summary_v");
            drop(statement, "cachedb_oracle_orders");
            drop(statement, "cachedb_oracle_customers");
            drop(statement, "cachedb_oracle_schema_entity");
            statement.executeUpdate("""
                    CREATE TABLE cachedb_oracle_customers (
                        customer_id NUMBER(19) NOT NULL PRIMARY KEY,
                        tax_number VARCHAR2(40 CHAR) NOT NULL,
                        customer_type VARCHAR2(40 CHAR) NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE cachedb_oracle_orders (
                        order_id NUMBER(19) NOT NULL PRIMARY KEY,
                        customer_id NUMBER(19) NOT NULL,
                        order_date DATE NOT NULL,
                        order_amount NUMBER(18,2) NOT NULL,
                        currency_code VARCHAR2(3 CHAR) NOT NULL,
                        order_type VARCHAR2(40 CHAR) NOT NULL,
                        entity_version NUMBER(19) NOT NULL,
                        CONSTRAINT fk_cachedb_oracle_orders_customer
                            FOREIGN KEY (customer_id) REFERENCES cachedb_oracle_customers(customer_id)
                    )
                    """);
            statement.executeUpdate("INSERT INTO cachedb_oracle_customers VALUES (1, 'TAX-1', 'VIP')");
            statement.executeUpdate("INSERT INTO cachedb_oracle_customers VALUES (2, 'TAX-2', 'STANDARD')");
            statement.executeUpdate("INSERT INTO cachedb_oracle_orders VALUES (101, 1, DATE '2026-01-01', 10, 'USD', 'ONLINE', 1)");
            statement.executeUpdate("INSERT INTO cachedb_oracle_orders VALUES (102, 1, DATE '2026-02-01', 20, 'USD', 'ONLINE', 2)");
            statement.executeUpdate("INSERT INTO cachedb_oracle_orders VALUES (201, 2, DATE '2026-03-01', 30, 'EUR', 'STORE', 3)");
            statement.executeUpdate("""
                    CREATE VIEW cachedb_oracle_order_summary_v AS
                    SELECT order_id, customer_id, order_date, order_amount, currency_code
                    FROM cachedb_oracle_orders
                    """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!reachable()) {
            return;
        }
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            dropView(statement, "cachedb_oracle_order_summary_v");
            drop(statement, "cachedb_oracle_orders");
            drop(statement, "cachedb_oracle_customers");
            drop(statement, "cachedb_oracle_schema_entity");
        }
    }

    @Test
    void shouldCreateValidateAndMigrateSchemaWithOracleDdl() throws Exception {
        DefaultEntityRegistry registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(schemaEntityMetadata(), null, CachePolicy.defaults(), null, null);
        CacheDatabaseSchemaAdmin schemaAdmin = new CacheDatabaseSchemaAdmin(
                dataSource(),
                registry,
                SchemaBootstrapConfig.builder()
                        .mode(SchemaBootstrapMode.CREATE_IF_MISSING)
                        .includeVersionColumn(true)
                        .build()
        );

        SchemaBootstrapResult created = schemaAdmin.createIfMissing();

        assertTrue(created.success(), () -> "Unexpected schema issues: " + created.issues());
        assertEquals(1, created.createdTableCount());
        String ddl = schemaAdmin.exportDdl().get("OracleSchemaEntity");
        assertTrue(ddl.startsWith("CREATE TABLE cachedb_oracle_schema_entity"));
        assertTrue(ddl.contains("payload VARCHAR2(4000 CHAR)"));
        assertTrue(ddl.contains("active NUMBER(1)"));
        assertTrue(ddl.contains("created_at TIMESTAMP(6) WITH TIME ZONE"));
        assertTrue(ddl.contains("entity_version NUMBER(19) DEFAULT 0 NOT NULL"));
        assertTrue(schemaAdmin.validate().success());
        assertTrue(schemaAdmin.planMigration().empty());

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE cachedb_oracle_schema_entity DROP COLUMN payload");
        }

        SchemaMigrationPlan plan = schemaAdmin.planMigration();
        assertEquals(1, plan.stepCount());
        assertEquals(
                "ALTER TABLE cachedb_oracle_schema_entity ADD payload VARCHAR2(4000 CHAR)",
                plan.steps().get(0).sql()
        );
        assertEquals(1, schemaAdmin.applyMigrationPlan().stepCount());
        assertTrue(schemaAdmin.validate().success());
    }

    @Test
    void shouldDiscoverWarmCompareAndEstimateOnOracle() throws Exception {
        DataSource dataSource = dataSource();
        MigrationSchemaDiscovery discovery = new MigrationSchemaDiscovery(
                dataSource,
                new DefaultEntityRegistry(ResourceLimits.defaults())
        );

        MigrationSchemaDiscovery.Result discovered = discovery.discover();

        assertTrue(discovered.tables().stream()
                .anyMatch(table -> table.tableName().equalsIgnoreCase("cachedb_oracle_orders")));
        assertTrue(discovered.tables().stream()
                .anyMatch(table -> table.tableName().equalsIgnoreCase("cachedb_oracle_order_summary_v")
                        && table.objectType().equalsIgnoreCase("VIEW")));
        assertFalse(discovered.tables().stream()
                .anyMatch(table -> table.schemaName().equalsIgnoreCase("SYS")));
        assertFalse(discovered.routeSuggestions().isEmpty());

        MigrationPlanner.Request request = new MigrationPlanner.Request(
                "oracle-customer-orders",
                "cachedb_oracle_customers", "customer_id",
                "cachedb_oracle_orders", "order_id",
                "customer_id", "order_date", "DESC",
                2L, 3L, 2L, 2L, 2, 2,
                true, false, false, false, true, false, true, true, true
        );
        MigrationWarmRunner warmRunner = new MigrationWarmRunner(dataSource, fakeHydrators());
        MigrationWarmRunner.Result warmResult = warmRunner.execute(new MigrationWarmRunner.Request(
                request, false, true, 10, 10, 10
        ));

        assertEquals(3L, warmResult.childRowsRead());
        assertTrue(warmResult.childWarmSql().contains("ROW_NUMBER() OVER"));
        assertFalse(warmResult.childWarmSql().endsWith(";"));

        MigrationComparisonRunner comparisonRunner = new MigrationComparisonRunner(
                dataSource,
                discovery,
                warmRunner,
                (plan, comparisonRequest) -> Optional.of(new MigrationComparisonRunner.CacheRouteExecutor() {
                    @Override public String routeLabel() { return "projection:oracle-test"; }
                    @Override public String idColumn() { return "order_id"; }
                    @Override public boolean usesProjection() { return true; }
                    @Override
                    public MigrationComparisonRunner.RoutePage execute(Object sampleRootId, int pageSize) {
                        return new MigrationComparisonRunner.RoutePage(2, List.of("102", "101"));
                    }
                })
        );
        MigrationComparisonRunner.Result comparison = comparisonRunner.execute(new MigrationComparisonRunner.Request(
                request, false, false, 10, 10, 10,
                "1", 1, 0, 1, 2, "", ""
        ));

        assertTrue(comparison.baselineSqlTemplate().contains("OFFSET 0 ROWS FETCH NEXT :page_size ROWS ONLY"));
        assertTrue(comparison.sampleComparisons().get(0).exactMatch());

        MigrationPlanner.Result plan = new MigrationPlanner().plan(request);
        MigrationRedisMemoryEstimator.Result estimate = new MigrationRedisMemoryEstimator(dataSource, discovery).estimate(plan);
        assertTrue(estimate.source().startsWith("ORACLE_"));
        assertTrue(estimate.estimatedTotalBytes() > 0L);
    }

    private MigrationWarmRunner.WarmEntityHydratorFactory fakeHydrators() {
        return surface -> {
            if (surface.equalsIgnoreCase("cachedb_oracle_orders")) {
                return Optional.of(fakeHydrator("OrderEntity", "cachedb_oracle_orders", "order_id"));
            }
            if (surface.equalsIgnoreCase("cachedb_oracle_customers")) {
                return Optional.of(fakeHydrator("CustomerEntity", "cachedb_oracle_customers", "customer_id"));
            }
            return Optional.empty();
        };
    }

    private EntityMetadata<SchemaEntity, Long> schemaEntityMetadata() {
        return new EntityMetadata<>() {
            @Override public String entityName() { return "OracleSchemaEntity"; }
            @Override public String tableName() { return "cachedb_oracle_schema_entity"; }
            @Override public String redisNamespace() { return "oracle-schema-entity"; }
            @Override public String idColumn() { return "id"; }
            @Override public Class<SchemaEntity> entityType() { return SchemaEntity.class; }
            @Override public Function<SchemaEntity, Long> idAccessor() { return SchemaEntity::id; }
            @Override public List<String> columns() { return List.of("id", "payload", "active", "created_at"); }
            @Override
            public Map<String, String> columnTypes() {
                return Map.of(
                        "id", Long.class.getName(),
                        "payload", String.class.getName(),
                        "active", Boolean.class.getName(),
                        "created_at", Instant.class.getName()
                );
            }
            @Override public List<com.reactor.cachedb.core.model.RelationDefinition> relations() { return List.of(); }
        };
    }

    private MigrationWarmRunner.WarmEntityHydrator fakeHydrator(String entity, String table, String idColumn) {
        return new MigrationWarmRunner.WarmEntityHydrator() {
            @Override public String entityName() { return entity; }
            @Override public String tableName() { return table; }
            @Override public String idColumn() { return idColumn; }
            @Override public String versionColumn() { return "entity_version"; }
            @Override public String deletedColumn() { return ""; }
            @Override public String deletedMarkerValue() { return "true"; }
            @Override public void hydrate(Map<String, Object> row, long version) {
                throw new AssertionError("Dry-run warm must not hydrate rows");
            }
        };
    }

    private DataSource dataSource() throws SQLException {
        OracleDataSource dataSource = new OracleDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
    }

    private static void drop(Statement statement, String table) throws SQLException {
        try {
            statement.executeUpdate("DROP TABLE " + table + " PURGE");
        } catch (SQLException exception) {
            if (Math.abs(exception.getErrorCode()) != 942) {
                throw exception;
            }
        }
    }

    private static void dropView(Statement statement, String view) throws SQLException {
        try {
            statement.executeUpdate("DROP VIEW " + view);
        } catch (SQLException exception) {
            if (Math.abs(exception.getErrorCode()) != 942) {
                throw exception;
            }
        }
    }

    private boolean reachable() {
        try (Connection ignored = open()) {
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private void assumeReachable() {
        boolean available = reachable();
        if (!available && Boolean.getBoolean("cachedb.it.oracle.required")) {
            fail("No reachable Oracle test database found at " + JDBC_URL);
        }
        Assumptions.assumeTrue(available, "No reachable Oracle test database found");
    }

    private record SchemaEntity(Long id, String payload, boolean active, Instant createdAt) {
    }
}
