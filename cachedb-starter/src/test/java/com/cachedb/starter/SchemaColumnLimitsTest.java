package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class SchemaColumnLimitsTest {
    @Test void oracleRejects1001BeforeTableInspectionOrDdl() {
        Fixture fixture = new Fixture("Oracle", 0);
        var admin = admin(fixture.source, 1001, false);
        var result = admin.createIfMissing();
        assertFalse(result.success());
        assertTrue(result.issues().get(0).issue().contains("1001 exceeds database dictionary limit 1000"));
        assertEquals(0, fixture.tableReads.get());
        assertThrows(IllegalArgumentException.class, admin::exportDdl);
        assertThrows(IllegalArgumentException.class, admin::planMigration);
    }

    @Test void postgresAccepts1200AndRejects1601() {
        Fixture fixture = new Fixture("PostgreSQL", 0);
        var accepted = admin(fixture.source, 1200, false);
        assertTrue(accepted.createIfMissing().success());
        assertTrue(accepted.validate().success());
        assertTrue(accepted.planMigration().empty());
        assertTrue(accepted.exportDdl().get("Wide").contains("c1199"));
        assertEquals(3, fixture.columnReads.get(), "one getColumns call per existing table, not per column");
        var rejected = admin(fixture.source, 1601, false);
        assertFalse(rejected.createIfMissing().success());
        assertThrows(IllegalArgumentException.class, rejected::exportDdl);
    }

    @Test void activeDictionaryOverridesFallbackAndRemainsGloballyBounded() {
        Fixture fixture = new Fixture("Oracle", 4096);
        assertTrue(admin(fixture.source, 4096, false).exportDdl().get("Wide").contains("c4095"));
        assertThrows(IllegalArgumentException.class, () -> admin(fixture.source, 4097, false).exportDdl());
        Fixture restricted = new Fixture("PostgreSQL", 1000);
        assertFalse(admin(restricted.source, 1200, false).createIfMissing().success());
        assertEquals(0, restricted.tableReads.get());
    }

    @Test void countsVersionAndDeletedColumnsIncludingMigrationOfExistingUnmappedColumns() throws Exception {
        Fixture fixture = new Fixture("Oracle", 1000);
        var accepted = admin(fixture.source, 998, true);
        assertTrue(accepted.exportDdl().get("Wide").contains("deleted"));
        assertFalse(admin(fixture.source, 999, true).createIfMissing().success());
        try (var connection = fixture.source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE wide_table (" + String.join(", ",
                    IntStream.range(0, 1000).mapToObj(i -> "c" + i + " INT").toList()) + ")");
        }
        assertThrows(IllegalArgumentException.class, accepted::planMigration,
                "unmapped existing columns must also count before adding version/deleted columns");
    }

    @Test void discoveryRetainsWiderViewsButRejectsOversizedPhysicalTables() throws Exception {
        Fixture fixture = new Fixture("PostgreSQL", 3);
        try (var connection = fixture.source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE wide_table (id INT, c1 INT, c2 INT)");
            statement.executeUpdate("CREATE VIEW wide_view AS SELECT *, 1 AS extra FROM wide_table");
        }
        var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        var result = new MigrationSchemaDiscovery(fixture.source, registry).discover();
        assertEquals(4, result.tables().stream().filter(t -> t.tableName().equalsIgnoreCase("wide_view"))
                .findFirst().orElseThrow().columns().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("table dictionary limit 3")));
        Fixture restricted = new Fixture("PostgreSQL", 2, fixture.database);
        assertThrows(IllegalArgumentException.class, () -> new MigrationSchemaDiscovery(restricted.source, registry).discover());
    }

    static CacheDatabaseSchemaAdmin admin(DataSource source, int columns, boolean extras) {
        var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(metadata(columns, extras), null, CachePolicy.defaults(), null, null);
        return new CacheDatabaseSchemaAdmin(source, registry, SchemaBootstrapConfig.builder()
                .includeVersionColumn(extras).includeDeletedColumn(extras).build());
    }

    static EntityMetadata<Long, Long> metadata(int count, boolean deleted) {
        List<String> columns = IntStream.range(0, count).mapToObj(i -> "c" + i).toList();
        return new EntityMetadata<>() {
            public String entityName() { return "Wide"; }
            public String tableName() { return "wide_table"; }
            public String redisNamespace() { return "wide"; }
            public String idColumn() { return "c0"; }
            public String deletedColumn() { return deleted ? "deleted" : null; }
            public Class<Long> entityType() { return Long.class; }
            public Function<Long, Long> idAccessor() { return Function.identity(); }
            public List<String> columns() { return columns; }
            public Map<String, String> columnTypes() {
                return columns.stream().collect(java.util.stream.Collectors.toMap(Function.identity(), c -> Integer.class.getName()));
            }
            public List<RelationDefinition> relations() { return List.of(); }
        };
    }

    private static final class Fixture {
        final JdbcDataSource database;
        final DataSource source;
        final AtomicInteger tableReads = new AtomicInteger();
        final AtomicInteger columnReads = new AtomicInteger();

        Fixture(String product, int limit) { this(product, limit, null); }
        Fixture(String product, int limit, JdbcDataSource existing) {
            database = existing == null ? new JdbcDataSource() : existing;
            if (existing == null) database.setURL("jdbc:h2:mem:columns_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("getConnection")) return invoke(method, database, args);
                        Connection connection = database.getConnection();
                        DatabaseMetaData metadata = connection.getMetaData();
                        Object decorated = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                                (p, m, a) -> {
                                    switch (m.getName()) {
                                        case "getDatabaseProductName": return product;
                                        case "getMaxColumnsInTable": return limit;
                                        case "getTables": tableReads.incrementAndGet(); break;
                                        case "getColumns": columnReads.incrementAndGet(); break;
                                    }
                                    return invoke(m, metadata, a);
                                });
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                                (p, m, a) -> m.getName().equals("getMetaData") ? decorated : invoke(m, connection, a));
                    });
        }
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
