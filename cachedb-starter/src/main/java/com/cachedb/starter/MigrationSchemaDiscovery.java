package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class MigrationSchemaDiscovery {

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema",
            "pg_catalog",
            "pg_toast",
            "sys",
            "mysql",
            "performance_schema"
    );

    private final DataSource dataSource;
    private final EntityRegistry entityRegistry;

    MigrationSchemaDiscovery(DataSource dataSource, EntityRegistry entityRegistry) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
    }

    Result discover() {
        Instant discoveredAt = Instant.now();
        LinkedHashMap<TableKey, MutableTable> tables = new LinkedHashMap<>();
        ArrayList<String> warnings = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            loadTables(metaData, catalog, tables);
            loadColumns(metaData, catalog, tables);
            loadPrimaryKeys(metaData, catalog, tables);
            loadImportedKeys(metaData, catalog, tables);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not inspect PostgreSQL schema for migration planning: " + exception.getMessage(), exception);
        }
        if (tables.isEmpty()) {
            warnings.add("No user tables were discovered from the configured DataSource.");
        }
        List<TableInfo> tableInfos = tables.values().stream()
                .map(MutableTable::freeze)
                .sorted(Comparator.comparing(TableInfo::qualifiedTableName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<RouteSuggestion> suggestions = buildSuggestions(tables, warnings);
        if (suggestions.isEmpty() && !tables.isEmpty()) {
            warnings.add("No single-column foreign key route suggestions were discovered. Composite or implicit relations may need manual planner input.");
        }
        return new Result(tableInfos, suggestions, List.copyOf(warnings), discoveredAt);
    }

    private void loadTables(DatabaseMetaData metaData, String catalog, Map<TableKey, MutableTable> tables) throws SQLException {
        try (ResultSet resultSet = metaData.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (resultSet.next()) {
                String schema = resultSet.getString("TABLE_SCHEM");
                if (isSystemSchema(schema)) {
                    continue;
                }
                String tableName = resultSet.getString("TABLE_NAME");
                if (tableName == null || tableName.isBlank()) {
                    continue;
                }
                TableKey key = new TableKey(schema, tableName);
                tables.put(key, new MutableTable(
                        schema,
                        tableName,
                        qualifiedName(schema, tableName),
                        resolveRegisteredEntityName(schema, tableName)
                ));
            }
        }
    }

    private void loadColumns(DatabaseMetaData metaData, String catalog, Map<TableKey, MutableTable> tables) throws SQLException {
        for (Map.Entry<TableKey, MutableTable> entry : tables.entrySet()) {
            TableKey key = entry.getKey();
            MutableTable table = entry.getValue();
            try (ResultSet resultSet = metaData.getColumns(catalog, key.schemaName(), key.tableName(), "%")) {
                while (resultSet.next()) {
                    String columnName = resultSet.getString("COLUMN_NAME");
                    int jdbcType = resultSet.getInt("DATA_TYPE");
                    String jdbcTypeName = resultSet.getString("TYPE_NAME");
                    boolean nullable = DatabaseMetaData.columnNullable == resultSet.getInt("NULLABLE");
                    table.columns.put(normalize(columnName), new MutableColumn(
                            columnName,
                            jdbcType,
                            jdbcTypeName == null ? "" : jdbcTypeName,
                            nullable
                    ));
                }
            }
        }
    }

    private void loadPrimaryKeys(DatabaseMetaData metaData, String catalog, Map<TableKey, MutableTable> tables) throws SQLException {
        for (Map.Entry<TableKey, MutableTable> entry : tables.entrySet()) {
            TableKey key = entry.getKey();
            MutableTable table = entry.getValue();
            try (ResultSet resultSet = metaData.getPrimaryKeys(catalog, key.schemaName(), key.tableName())) {
                ArrayList<String> primaryKeys = new ArrayList<>();
                while (resultSet.next()) {
                    primaryKeys.add(resultSet.getString("COLUMN_NAME"));
                }
                primaryKeys.sort(String.CASE_INSENSITIVE_ORDER);
                if (!primaryKeys.isEmpty()) {
                    table.primaryKeyColumn = primaryKeys.get(0);
                    MutableColumn column = table.columns.get(normalize(table.primaryKeyColumn));
                    if (column != null) {
                        column.primaryKey = true;
                    }
                }
            }
        }
    }

    private void loadImportedKeys(DatabaseMetaData metaData, String catalog, Map<TableKey, MutableTable> tables) throws SQLException {
        for (Map.Entry<TableKey, MutableTable> entry : tables.entrySet()) {
            TableKey key = entry.getKey();
            MutableTable table = entry.getValue();
            try (ResultSet resultSet = metaData.getImportedKeys(catalog, key.schemaName(), key.tableName())) {
                while (resultSet.next()) {
                    String foreignKeyColumn = resultSet.getString("FKCOLUMN_NAME");
                    String parentSchema = resultSet.getString("PKTABLE_SCHEM");
                    String parentTable = resultSet.getString("PKTABLE_NAME");
                    String parentColumn = resultSet.getString("PKCOLUMN_NAME");
                    String foreignKeyName = resultSet.getString("FK_NAME");
                    MutableColumn column = table.columns.get(normalize(foreignKeyColumn));
                    if (column != null) {
                        column.foreignKey = true;
                    }
                    table.importedKeys.add(new ImportedKey(
                            foreignKeyName == null || foreignKeyName.isBlank()
                                    ? table.tableName + ":" + foreignKeyColumn + ":" + parentTable
                                    : foreignKeyName,
                            foreignKeyColumn,
                            parentSchema,
                            parentTable,
                            parentColumn
                    ));
                }
            }
        }
    }

    private List<RouteSuggestion> buildSuggestions(Map<TableKey, MutableTable> tables, List<String> warnings) {
        ArrayList<RouteSuggestion> suggestions = new ArrayList<>();
        MigrationPlanner.Request defaults = MigrationPlanner.Request.defaults();
        for (MutableTable childTable : tables.values()) {
            LinkedHashMap<String, List<ImportedKey>> grouped = new LinkedHashMap<>();
            for (ImportedKey importedKey : childTable.importedKeys) {
                grouped.computeIfAbsent(importedKey.foreignKeyName(), ignored -> new ArrayList<>()).add(importedKey);
            }
            for (List<ImportedKey> group : grouped.values()) {
                if (group.size() != 1) {
                    warnings.add("Composite foreign key discovered on " + childTable.qualifiedTableName + ". The planner UI currently seeds only single-column relations.");
                    continue;
                }
                ImportedKey importedKey = group.get(0);
                MutableTable rootTable = tables.get(new TableKey(importedKey.parentSchema(), importedKey.parentTable()));
                if (rootTable == null) {
                    continue;
                }
                List<String> sortCandidates = childTable.sortCandidates();
                String selectedSortColumn = sortCandidates.isEmpty()
                        ? firstNonBlank(childTable.primaryKeyColumn, importedKey.foreignKeyColumn())
                        : sortCandidates.get(0);
                boolean rankedSortCandidate = isRankLikeColumn(selectedSortColumn);
                boolean temporalSortCandidate = isTemporalColumnName(selectedSortColumn);
                String rootSurface = firstNonBlank(rootTable.registeredEntityName, rootTable.qualifiedTableName);
                String childSurface = firstNonBlank(childTable.registeredEntityName, childTable.qualifiedTableName);
                MigrationPlanner.Request plannerRequest = new MigrationPlanner.Request(
                        rootTable.tableName + "-" + childTable.tableName,
                        rootSurface,
                        firstNonBlank(rootTable.primaryKeyColumn, importedKey.parentColumn()),
                        childSurface,
                        firstNonBlank(childTable.primaryKeyColumn, importedKey.foreignKeyColumn()),
                        importedKey.foreignKeyColumn(),
                        selectedSortColumn,
                        "DESC",
                        0L,
                        0L,
                        defaults.typicalChildrenPerRoot(),
                        defaults.maxChildrenPerRoot(),
                        defaults.firstPageSize(),
                        defaults.hotWindowPerRoot(),
                        true,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        true
                );
                String summary = "Detected foreign key "
                        + childTable.qualifiedTableName + "." + importedKey.foreignKeyColumn()
                        + " -> " + rootTable.qualifiedTableName + "." + importedKey.parentColumn()
                        + ". Suggested default sort is " + selectedSortColumn + " DESC"
                        + (rankedSortCandidate
                        ? " If this screen is a top-N or threshold route, enable global sorted / threshold mode before planning."
                        : ".");
                suggestions.add(new RouteSuggestion(
                        rootTable.qualifiedTableName + " -> " + childTable.qualifiedTableName,
                        summary,
                        rootSurface,
                        firstNonBlank(rootTable.registeredEntityName, ""),
                        firstNonBlank(rootTable.primaryKeyColumn, importedKey.parentColumn()),
                        childSurface,
                        firstNonBlank(childTable.registeredEntityName, ""),
                        firstNonBlank(childTable.primaryKeyColumn, importedKey.foreignKeyColumn()),
                        importedKey.foreignKeyColumn(),
                        selectedSortColumn,
                        List.copyOf(sortCandidates),
                        rankedSortCandidate,
                        temporalSortCandidate,
                        plannerRequest
                ));
            }
        }
        suggestions.sort(Comparator.comparing(RouteSuggestion::label, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(suggestions);
    }

    private String resolveRegisteredEntityName(String schema, String tableName) {
        Collection<EntityBinding<?, ?>> bindings = entityRegistry.all();
        String qualified = qualifiedName(schema, tableName);
        for (EntityBinding<?, ?> binding : bindings) {
            String bindingTable = binding.metadata().tableName();
            if (bindingTable.equalsIgnoreCase(tableName) || bindingTable.equalsIgnoreCase(qualified)) {
                return binding.metadata().entityName();
            }
        }
        return "";
    }

    private boolean isSystemSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        return SYSTEM_SCHEMAS.contains(schema.trim().toLowerCase(Locale.ROOT));
    }

    private String qualifiedName(String schema, String tableName) {
        if (schema == null || schema.isBlank() || "public".equalsIgnoreCase(schema)) {
            return tableName;
        }
        return schema + "." + tableName;
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isTemporalType(int jdbcType) {
        return jdbcType == Types.DATE
                || jdbcType == Types.TIME
                || jdbcType == Types.TIME_WITH_TIMEZONE
                || jdbcType == Types.TIMESTAMP
                || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE;
    }

    private boolean isNumericType(int jdbcType) {
        return jdbcType == Types.BIGINT
                || jdbcType == Types.INTEGER
                || jdbcType == Types.SMALLINT
                || jdbcType == Types.TINYINT
                || jdbcType == Types.NUMERIC
                || jdbcType == Types.DECIMAL
                || jdbcType == Types.DOUBLE
                || jdbcType == Types.FLOAT
                || jdbcType == Types.REAL;
    }

    private boolean isTemporalColumnName(String columnName) {
        String normalized = normalize(columnName);
        return normalized.endsWith("_at")
                || normalized.endsWith("_date")
                || normalized.contains("timestamp")
                || normalized.contains("created")
                || normalized.contains("updated")
                || normalized.contains("ordered")
                || normalized.contains("event");
    }

    private boolean isRankLikeColumn(String columnName) {
        String normalized = normalize(columnName);
        return normalized.contains("rank")
                || normalized.contains("score")
                || normalized.contains("amount")
                || normalized.contains("total")
                || normalized.contains("value")
                || normalized.contains("count");
    }

    record Result(
            List<TableInfo> tables,
            List<RouteSuggestion> routeSuggestions,
            List<String> warnings,
            Instant discoveredAt
    ) {
    }

    record TableInfo(
            String schemaName,
            String tableName,
            String qualifiedTableName,
            String registeredEntityName,
            String primaryKeyColumn,
            int columnCount,
            int importedKeyCount,
            List<String> temporalColumns,
            List<String> foreignKeyColumns,
            List<ColumnInfo> columns
    ) {
    }

    record ColumnInfo(
            String name,
            String jdbcTypeName,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey,
            boolean temporal,
            boolean numeric
    ) {
    }

    record RouteSuggestion(
            String label,
            String summary,
            String rootSurface,
            String rootEntityName,
            String rootPrimaryKeyColumn,
            String childSurface,
            String childEntityName,
            String childPrimaryKeyColumn,
            String relationColumn,
            String sortColumn,
            List<String> sortCandidates,
            boolean rankedSortCandidate,
            boolean temporalSortCandidate,
            MigrationPlanner.Request plannerRequest
    ) {
    }

    private record TableKey(String schemaName, String tableName) {
    }

    private record ImportedKey(
            String foreignKeyName,
            String foreignKeyColumn,
            String parentSchema,
            String parentTable,
            String parentColumn
    ) {
    }

    private final class MutableTable {
        private final String schemaName;
        private final String tableName;
        private final String qualifiedTableName;
        private final String registeredEntityName;
        private final LinkedHashMap<String, MutableColumn> columns = new LinkedHashMap<>();
        private final ArrayList<ImportedKey> importedKeys = new ArrayList<>();
        private String primaryKeyColumn = "";

        private MutableTable(String schemaName, String tableName, String qualifiedTableName, String registeredEntityName) {
            this.schemaName = schemaName == null ? "" : schemaName;
            this.tableName = tableName;
            this.qualifiedTableName = qualifiedTableName;
            this.registeredEntityName = registeredEntityName == null ? "" : registeredEntityName;
        }

        private List<String> sortCandidates() {
            return columns.values().stream()
                    .sorted(Comparator
                            .comparingInt(this::sortScore)
                            .reversed()
                            .thenComparing(MutableColumn::name, String.CASE_INSENSITIVE_ORDER))
                    .filter(column -> sortScore(column) > 0)
                    .map(MutableColumn::name)
                    .toList();
        }

        private int sortScore(MutableColumn column) {
            if (column.primaryKey) {
                return 10;
            }
            if (isTemporalColumnName(column.name) && column.temporal()) {
                return 120;
            }
            if (column.temporal()) {
                return 100;
            }
            if (isRankLikeColumn(column.name) && column.numeric()) {
                return 80;
            }
            if (column.numeric()) {
                return 40;
            }
            return 0;
        }

        private TableInfo freeze() {
            ArrayList<String> temporalColumns = new ArrayList<>();
            ArrayList<String> foreignKeyColumns = new ArrayList<>();
            ArrayList<ColumnInfo> columnInfos = new ArrayList<>();
            for (MutableColumn column : columns.values()) {
                if (column.temporal()) {
                    temporalColumns.add(column.name);
                }
                if (column.foreignKey) {
                    foreignKeyColumns.add(column.name);
                }
                columnInfos.add(new ColumnInfo(
                        column.name,
                        column.jdbcTypeName,
                        column.nullable,
                        column.primaryKey,
                        column.foreignKey,
                        column.temporal(),
                        column.numeric()
                ));
            }
            return new TableInfo(
                    schemaName,
                    tableName,
                    qualifiedTableName,
                    registeredEntityName,
                    primaryKeyColumn,
                    columns.size(),
                    importedKeys.size(),
                    List.copyOf(temporalColumns),
                    List.copyOf(foreignKeyColumns),
                    List.copyOf(columnInfos)
            );
        }
    }

    private final class MutableColumn {
        private final String name;
        private final int jdbcType;
        private final String jdbcTypeName;
        private final boolean nullable;
        private boolean primaryKey;
        private boolean foreignKey;

        private MutableColumn(String name, int jdbcType, String jdbcTypeName, boolean nullable) {
            this.name = name;
            this.jdbcType = jdbcType;
            this.jdbcTypeName = jdbcTypeName;
            this.nullable = nullable;
        }

        private String name() {
            return name;
        }

        private boolean temporal() {
            return isTemporalType(jdbcType);
        }

        private boolean numeric() {
            return isNumericType(jdbcType);
        }
    }
}
