package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.queue.SchemaMigrationPlan;
import com.reactor.cachedb.core.queue.SchemaMigrationStep;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.jdbc.JdbcSchemaDialect;
import com.reactor.cachedb.jdbc.JdbcSchemaDialects;

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
            JdbcSchemaDialect dialect = JdbcSchemaDialects.resolve(connection);
            for (EntityBinding<?, ?> binding : entityRegistry.all()) {
                EntityMetadata<?, ?> metadata = binding.metadata();
                String tableName = qualifiedTableName(metadata.tableName());
                boolean exists = tableExists(connection, dialect, tableName);
                if (!exists && mode == SchemaBootstrapMode.CREATE_IF_MISSING) {
                    createTable(connection, dialect, metadata, tableName);
                    createdTables.add(tableName);
                    exists = true;
                }
                if (!exists) {
                    issues.add(new SchemaBootstrapIssue(metadata.entityName(), tableName, "Missing table"));
                    continue;
                }
                List<String> missingColumns = missingColumns(connection, dialect, metadata, tableName);
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
        JdbcSchemaDialect dialect = JdbcSchemaDialects.resolve(dataSource);
        LinkedHashMap<String, String> ddl = new LinkedHashMap<>();
        for (EntityBinding<?, ?> binding : entityRegistry.all()) {
            EntityMetadata<?, ?> metadata = binding.metadata();
            ddl.put(metadata.entityName(), createTableSql(
                    dialect,
                    metadata,
                    qualifiedTableName(metadata.tableName())
            ));
        }
        return Map.copyOf(ddl);
    }

    public SchemaMigrationPlan planMigration() {
        ArrayList<SchemaMigrationStep> steps = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            JdbcSchemaDialect dialect = JdbcSchemaDialects.resolve(connection);
            for (EntityBinding<?, ?> binding : entityRegistry.all()) {
                EntityMetadata<?, ?> metadata = binding.metadata();
                String tableName = qualifiedTableName(metadata.tableName());
                if (!tableExists(connection, dialect, tableName)) {
                    steps.add(new SchemaMigrationStep(
                            metadata.entityName(),
                            tableName,
                            createTableSql(dialect, metadata, tableName),
                            "Create missing table"
                    ));
                    continue;
                }
                for (String column : expectedColumns(metadata)) {
                    if (!columnExists(connection, dialect, tableName, column)) {
                        steps.add(new SchemaMigrationStep(
                                metadata.entityName(),
                                tableName,
                                dialect.addColumnSql(tableName, columnDefinition(dialect, metadata, column)),
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

    private void createTable(
            Connection connection,
            JdbcSchemaDialect dialect,
            EntityMetadata<?, ?> metadata,
            String tableName
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.executeUpdate(createTableSql(dialect, metadata, tableName));
            } catch (SQLException exception) {
                if (!tableExists(connection, dialect, tableName)) {
                    throw exception;
                }
            }
        }
    }

    private String createTableSql(
            JdbcSchemaDialect dialect,
            EntityMetadata<?, ?> metadata,
            String tableName
    ) {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        for (String column : metadata.columns()) {
            columns.put(column, columnDefinition(dialect, metadata, column));
        }
        if (config.includeVersionColumn() && !columns.containsKey(metadata.versionColumn())) {
            columns.put(metadata.versionColumn(), dialect.versionColumnDefinition(metadata.versionColumn()));
        }
        if (config.includeDeletedColumn()
                && metadata.deletedColumn() != null
                && !metadata.deletedColumn().isBlank()
                && !columns.containsKey(metadata.deletedColumn())) {
            columns.put(metadata.deletedColumn(), dialect.columnDefinition(
                    metadata.deletedColumn(),
                    String.class.getName(),
                    false
            ));
        }
        return dialect.createTableSql(tableName, List.copyOf(columns.values()), metadata.idColumn());
    }

    private String columnDefinition(
            JdbcSchemaDialect dialect,
            EntityMetadata<?, ?> metadata,
            String column
    ) {
        if (column.equals(metadata.versionColumn()) && !metadata.columns().contains(column)) {
            return dialect.versionColumnDefinition(column);
        }
        return dialect.columnDefinition(
                column,
                metadata.columnTypes().get(column),
                metadata.idColumn().equals(column)
        );
    }

    private boolean tableExists(
            Connection connection,
            JdbcSchemaDialect dialect,
            String tableName
    ) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(
                connection.getCatalog(),
                schemaPattern(connection, dialect),
                dialect.metadataIdentifier(metaData, normalizedTableName(tableName)),
                new String[]{"TABLE"}
        )) {
            return resultSet.next();
        }
    }

    private List<String> missingColumns(
            Connection connection,
            JdbcSchemaDialect dialect,
            EntityMetadata<?, ?> metadata,
            String tableName
    ) throws SQLException {
        ArrayList<String> missing = new ArrayList<>();
        for (String column : expectedColumns(metadata)) {
            if (!columnExists(connection, dialect, tableName, column)) {
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

    private String schemaPattern(Connection connection, JdbcSchemaDialect dialect) throws SQLException {
        String schemaName = config.schemaName();
        if (schemaName.isBlank()) {
            try {
                schemaName = connection.getSchema();
            } catch (SQLException ignored) {
                schemaName = "";
            }
        }
        return schemaName == null || schemaName.isBlank()
                ? null
                : dialect.metadataIdentifier(connection.getMetaData(), schemaName);
    }

    private boolean columnExists(
            Connection connection,
            JdbcSchemaDialect dialect,
            String tableName,
            String column
    ) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(
                connection.getCatalog(),
                schemaPattern(connection, dialect),
                dialect.metadataIdentifier(metaData, normalizedTableName(tableName)),
                dialect.metadataIdentifier(metaData, column)
        )) {
            return resultSet.next();
        }
    }
}
