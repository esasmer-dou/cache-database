package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.WriteBehindFlusher;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.jdbc.JdbcDatabaseDialect;
import com.reactor.cachedb.mssql.MssqlDatabaseDialect;
import com.reactor.cachedb.mssql.MssqlWriteBehindFlusher;
import com.reactor.cachedb.oracle.OracleDatabaseDialect;
import com.reactor.cachedb.oracle.OracleWriteBehindFlusher;
import com.reactor.cachedb.postgres.PostgresDatabaseDialect;
import com.reactor.cachedb.postgres.PostgresWriteBehindFlusher;
import com.reactor.cachedb.starter.CacheDatabaseSchemaAdmin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Disposable real databases only: exercises DDL, full-width insert/update/delete, and rejection. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
class ProviderColumnLimitsIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void enforcesActiveDictionaryAndPreservesFullWidthWrites(String provider) throws Exception {
        JdbcDatabaseDialect dialect = switch (provider) {
            case "postgres" -> new PostgresDatabaseDialect();
            case "mssql" -> new MssqlDatabaseDialect();
            default -> new OracleDatabaseDialect();
        };
        var config = new HikariConfig();
        config.setJdbcUrl(required(provider, "url"));
        config.setUsername(required(provider, "user"));
        config.setPassword(required(provider, "password"));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10000);
        String table = "wc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (var source = new HikariDataSource(config)) {
            int limit;
            try (var connection = source.getConnection()) {
                int reported = connection.getMetaData().getMaxColumnsInTable();
                limit = dialect.resolveMaxColumnsInTable(connection.getMetaData());
                System.out.println("COLUMN_DICTIONARY provider=" + provider + " reported=" + reported + " effective=" + limit);
                assertTrue(limit > 0 && limit <= 4096);
            }
            var metadata = metadata(table, limit);
            var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
            registry.register(metadata, null, CachePolicy.defaults(), null, null);
            var schema = new CacheDatabaseSchemaAdmin(source, registry, SchemaBootstrapConfig.defaults());
            boolean created = false;
            try {
                var result = schema.createIfMissing();
                assertTrue(result.success(), () -> result.issues().toString());
                created = true;
                assertTrue(schema.validate().success());
                assertTrue(schema.planMigration().empty());
                WriteBehindFlusher flusher = switch (provider) {
                    case "postgres" -> new PostgresWriteBehindFlusher(source, registry);
                    case "mssql" -> new MssqlWriteBehindFlusher(source, registry);
                    default -> new OracleWriteBehindFlusher(source, registry);
                };
                var columns = new LinkedHashMap<String,String>();
                for (String column : metadata.columns()) columns.put(column, "0");
                columns.put("c0", "1");
                columns.put("entity_version", "1");
                flusher.flush(operation(table, columns, 1, OperationType.UPSERT));
                columns.put("c" + (limit - 2), "7");
                columns.put("entity_version", "2");
                flusher.flush(operation(table, columns, 2, OperationType.UPSERT));
                try (var connection = source.getConnection(); var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT c" + (limit - 2) + ", entity_version FROM " + table + " WHERE c0=1")) {
                    assertTrue(rows.next());
                    assertEquals(7, rows.getInt(1));
                    assertEquals(2, rows.getLong(2));
                    assertFalse(rows.next());
                }
                columns.put("overflow", "1");
                assertThrows(IllegalArgumentException.class, () -> flusher.flush(operation(table, columns, 3, OperationType.UPSERT)));
                columns.remove("overflow");
                flusher.flush(operation(table, Map.of(), 4, OperationType.DELETE));
                try (var connection = source.getConnection(); var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
                var tooWide = new DefaultEntityRegistry(ResourceLimits.defaults());
                tooWide.register(metadata(table, limit + 1), null, CachePolicy.defaults(), null, null);
                var rejected = new CacheDatabaseSchemaAdmin(source, tooWide, SchemaBootstrapConfig.defaults());
                assertFalse(rejected.createIfMissing().success());
                assertThrows(IllegalArgumentException.class, rejected::exportDdl);
            } finally {
                if (created) try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                    statement.executeUpdate("DROP TABLE " + table + (provider.equals("oracle") ? " PURGE" : ""));
                }
            }
        }
    }

    private static QueuedWriteOperation operation(String table, Map<String,String> columns, long version, OperationType type) {
        return new QueuedWriteOperation(type, "WideLive", table, table, "column-limit", "c0", "entity_version",
                null, "1", Map.copyOf(columns), version, Instant.EPOCH);
    }

    private static String required(String provider, String field) {
        String value = TestFixtureSettings.value("cachedb.snapshot." + provider + "." + field, "");
        if (value.isBlank()) throw new IllegalStateException("Explicit SQL fixture setting required: " + provider + "." + field);
        return value;
    }

    private static EntityMetadata<Long, Long> metadata(String table, int count) {
        var columns = new ArrayList<String>();
        for (int i = 0; i < count - 1; i++) columns.add("c" + i);
        columns.add("entity_version");
        var types = new LinkedHashMap<String,String>();
        for (String column : columns) types.put(column, Integer.class.getName());
        types.put("c0", Long.class.getName());
        types.put("entity_version", Long.class.getName());
        return new EntityMetadata<>() {
            public String entityName() { return "WideLive"; }
            public String tableName() { return table; }
            public String redisNamespace() { return table; }
            public String idColumn() { return "c0"; }
            public Class<Long> entityType() { return Long.class; }
            public Function<Long, Long> idAccessor() { return Function.identity(); }
            public List<String> columns() { return List.copyOf(columns); }
            public Map<String,String> columnTypes() { return Map.copyOf(types); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
    }
}
