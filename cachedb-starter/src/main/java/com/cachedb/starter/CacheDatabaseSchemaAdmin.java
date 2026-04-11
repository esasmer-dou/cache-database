package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.queue.SchemaMigrationPlan;
import com.reactor.cachedb.core.queue.SchemaMigrationStep;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CacheDatabaseSchemaAdmin {

    private final DataSource dataSource;
    private final EntityRegistry entityRegistry;
    private final SchemaBootstrapConfig config;
    private final List<SchemaMigrationHistoryEntry> migrationHistory = Collections.synchronizedList(new ArrayList<>());

    public CacheDatabaseSchemaAdmin(
            DataSource dataSource,
            EntityRegistry entityRegistry,
            SchemaBootstrapConfig config
    ) {
        this.dataSource = dataSource;
        this.entityRegistry = entityRegistry;
        this.config = config;
    }

    public SchemaBootstrapResult applyConfiguredMode() {
        return bootstrap(config.mode());
    }

    public SchemaBootstrapResult validate() {
        return bootstrap(SchemaBootstrapMode.VALIDATE_ONLY);
    }

    public SchemaBootstrapResult createIfMissing() {
        return bootstrap(SchemaBootstrapMode.CREATE_IF_MISSING);
    }

    public SchemaBootstrapResult bootstrap(SchemaBootstrapMode mode) {
        ArrayList<String> createdTables = new ArrayList<>();
        ArrayList<String> validatedTables = new ArrayList<>();
        ArrayList<SchemaBootstrapIssue> issues = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            for (EntityBinding<?, ?> binding : entityRegistry.all()) {
                EntityMetadata<?, ?> metadata = binding.metadata();
                String tableName = qualifiedTableName(metadata.tableName());
                boolean exists = tableExists(connection, tableName);
                if (!exists && mode == SchemaBootstrapMode.CREATE_IF_MISSING) {
                    createTable(connection, metadata, tableName);
                    createdTables.add(tableName);
                    exists = true;
                }
                if (!exists) {
                    issues.add(new SchemaBootstrapIssue(metadata.entityName(), tableName, "Missing table"));
                    continue;
                }
                List<String> missingColumns = missingColumns(connection, metadata, tableName);
                if (!missingColumns.isEmpty()) {
                    issues.add(new SchemaBootstrapIssue(
                            metadata.entityName(),
                            tableName,
                            "Missing columns: " + String.join(", ", missingColumns)
                    ));
                    continue;
                }
                validatedTables.add(tableName);
            }
        } catch (SQLException exception) {
            issues.add(new SchemaBootstrapIssue("*", "*", exception.getClass().getSimpleName() + ": " + exception.getMessage()));
        }

        return new SchemaBootstrapResult(
                mode.name(),
                entityRegistry.all().size(),
                createdTables.size(),
                validatedTables.size(),
                List.copyOf(createdTables),
                List.copyOf(validatedTables),
                List.copyOf(issues),
                Instant.now()
        );
    }

    public Map<String, String> exportDdl() {
        LinkedHashMap<String, String> ddl = new LinkedHashMap<>();
        for (EntityBinding<?, ?> binding : entityRegistry.all()) {
            EntityMetadata<?, ?> metadata = binding.metadata();
            ddl.put(metadata.entityName(), createTableSql(metadata, qualifiedTableName(metadata.tableName())));
        }
        return Map.copyOf(ddl);
    }

    public SchemaMigrationPlan planMigration() {
        ArrayList<SchemaMigrationStep> steps = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (EntityBinding<?, ?> binding : entityRegistry.all()) {
                EntityMetadata<?, ?> metadata = binding.metadata();
                String tableName = qualifiedTableName(metadata.tableName());
                if (!tableExists(connection, tableName)) {
                    steps.add(new SchemaMigrationStep(
                            metadata.entityName(),
                            tableName,
                            createTableSql(metadata, tableName),
                            "Create missing table"
                    ));
                    continue;
                }
                for (String column : expectedColumns(metadata)) {
                    if (!columnExists(connection, tableName, column)) {
                        steps.add(new SchemaMigrationStep(
                                metadata.entityName(),
                                tableName,
                                "ALTER TABLE " + tableName + " ADD COLUMN " + columnDefinition(metadata, column),
                                "Add missing column " + column
                        ));
                    }
                }
            }
        } catch (SQLException exception) {
            steps.add(new SchemaMigrationStep("*", "*", "", exception.getClass().getSimpleName() + ": " + exception.getMessage()));
        }
        return new SchemaMigrationPlan(
                entityRegistry.all().size(),
                steps.size(),
                List.copyOf(steps),
                Instant.now()
        );
    }

    public SchemaMigrationPlan applyMigrationPlan() {
        SchemaMigrationPlan plan = planMigration();
        if (plan.empty()) {
            migrationHistory.add(new SchemaMigrationHistoryEntry(
                    "APPLY",
                    true,
                    true,
                    0,
                    0,
                    "",
                    Instant.now(),
                    List.of()
            ));
            return plan;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            int executedStepCount = 0;
            for (SchemaMigrationStep step : plan.steps()) {
                if (step.sql() != null && !step.sql().isBlank()) {
                    statement.executeUpdate(step.sql());
                    executedStepCount++;
                }
            }
            migrationHistory.add(new SchemaMigrationHistoryEntry(
                    "APPLY",
                    true,
                    true,
                    plan.stepCount(),
                    executedStepCount,
                    "",
                    Instant.now(),
                    plan.steps()
            ));
        } catch (SQLException exception) {
            ArrayList<SchemaMigrationStep> failed = new ArrayList<>(plan.steps());
            failed.add(new SchemaMigrationStep("*", "*", "", exception.getClass().getSimpleName() + ": " + exception.getMessage()));
            migrationHistory.add(new SchemaMigrationHistoryEntry(
                    "APPLY",
                    true,
                    false,
                    plan.stepCount(),
                    0,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    Instant.now(),
                    List.copyOf(failed)
            ));
            return new SchemaMigrationPlan(plan.tableCount(), failed.size(), List.copyOf(failed), Instant.now());
        }
        return plan;
    }

    public List<SchemaMigrationHistoryEntry> migrationHistory(int limit) {
        ArrayList<SchemaMigrationHistoryEntry> snapshot;
        synchronized (migrationHistory) {
            snapshot = new ArrayList<>(migrationHistory);
        }
        int fromIndex = Math.max(0, snapshot.size() - Math.max(0, limit));
        ArrayList<SchemaMigrationHistoryEntry> tail = new ArrayList<>(snapshot.subList(fromIndex, snapshot.size()));
        Collections.reverse(tail);
        return List.copyOf(tail);
    }

    private void createTable(Connection connection, EntityMetadata<?, ?> metadata, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(createTableSql(metadata, tableName));
        }
    }

    private String createTableSql(EntityMetadata<?, ?> metadata, String tableName) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        for (String column : metadata.columns()) {
            columns.put(column, columnDefinition(metadata, column));
        }
        if (config.includeVersionColumn() && !columns.containsKey(metadata.versionColumn())) {
            columns.put(metadata.versionColumn(), metadata.versionColumn() + " BIGINT NOT NULL DEFAULT 0");
        }
        if (config.includeDeletedColumn()
                && metadata.deletedColumn() != null
                && !metadata.deletedColumn().isBlank()
                && !columns.containsKey(metadata.deletedColumn())) {
            columns.put(metadata.deletedColumn(), metadata.deletedColumn() + " TEXT");
        }
        StringBuilder builder = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(tableName)
                .append(" (");
        int index = 0;
        for (String definition : columns.values()) {
            if (index++ > 0) {
                builder.append(", ");
            }
            builder.append(definition);
        }
        builder.append(", PRIMARY KEY (").append(metadata.idColumn()).append("))");
        return builder.toString();
    }

    private String columnDefinition(EntityMetadata<?, ?> metadata, String column) {
        String sqlType = sqlType(metadata.columnTypes().get(column));
        String notNull = metadata.idColumn().equals(column) ? " NOT NULL" : "";
        return column + " " + sqlType + notNull;
    }

    private String sqlType(String javaType) {
        if (javaType == null || javaType.isBlank()) {
            return "TEXT";
        }
        return switch (javaType) {
            case "java.lang.Long", "long" -> "BIGINT";
            case "java.lang.Integer", "int" -> "INTEGER";
            case "java.lang.Double", "double" -> "DOUBLE PRECISION";
            case "java.lang.Float", "float" -> "REAL";
            case "java.lang.Boolean", "boolean" -> "BOOLEAN";
            case "java.time.Instant", "java.time.LocalDateTime", "java.time.OffsetDateTime" -> "TIMESTAMP";
            case "java.time.LocalDate" -> "DATE";
            default -> "TEXT";
        };
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), schemaPattern(), normalizedTableName(tableName), new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private List<String> missingColumns(Connection connection, EntityMetadata<?, ?> metadata, String tableName) throws SQLException {
        ArrayList<String> missing = new ArrayList<>();
        for (String column : expectedColumns(metadata)) {
            if (!columnExists(connection, tableName, column)) {
                missing.add(column);
            }
        }
        return List.copyOf(missing);
    }

    private List<String> expectedColumns(EntityMetadata<?, ?> metadata) {
        ArrayList<String> columns = new ArrayList<>(metadata.columns());
        if (config.includeVersionColumn() && !columns.contains(metadata.versionColumn())) {
            columns.add(metadata.versionColumn());
        }
        if (config.includeDeletedColumn()
                && metadata.deletedColumn() != null
                && !metadata.deletedColumn().isBlank()
                && !columns.contains(metadata.deletedColumn())) {
            columns.add(metadata.deletedColumn());
        }
        return List.copyOf(columns);
    }

    private String qualifiedTableName(String tableName) {
        return config.schemaName().isBlank() ? tableName : config.schemaName() + "." + tableName;
    }

    private String normalizedTableName(String tableName) {
        int separator = tableName.indexOf('.');
        return separator >= 0 ? tableName.substring(separator + 1) : tableName;
    }

    private String schemaPattern() {
        return config.schemaName().isBlank() ? null : config.schemaName().toLowerCase(Locale.ROOT);
    }

    private boolean columnExists(Connection connection, String tableName, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), schemaPattern(), normalizedTableName(tableName), column)) {
            return resultSet.next();
        }
    }
}
