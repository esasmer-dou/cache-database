package com.reactor.cachedb.postgres;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.config.EntityFlushPolicy;
import com.reactor.cachedb.core.config.PersistenceSemantics;
import com.reactor.cachedb.core.queue.FailureClassifyingFlusher;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteFailureDetails;
import com.reactor.cachedb.core.queue.WriteBehindFlusher;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class PostgresWriteBehindFlusher implements FailureClassifyingFlusher {

    private final DataSource dataSource;
    private final EntityRegistry entityRegistry;
    private final PostgresFailureClassifier failureClassifier;
    private final WriteBehindConfig config;
    private final StoragePerformanceCollector performanceCollector;

    public PostgresWriteBehindFlusher(DataSource dataSource, EntityRegistry entityRegistry) {
        this(dataSource, entityRegistry, WriteBehindConfig.defaults(), null);
    }

    public PostgresWriteBehindFlusher(DataSource dataSource, EntityRegistry entityRegistry, WriteBehindConfig config) {
        this(dataSource, entityRegistry, config, null);
    }

    public PostgresWriteBehindFlusher(
            DataSource dataSource,
            EntityRegistry entityRegistry,
            WriteBehindConfig config,
            StoragePerformanceCollector performanceCollector
    ) {
        this.dataSource = dataSource;
        this.entityRegistry = entityRegistry;
        this.failureClassifier = new PostgresFailureClassifier();
        this.config = config;
        this.performanceCollector = performanceCollector;
    }

    @Override
    public void flush(QueuedWriteOperation operation) throws SQLException {
        long startedAt = System.nanoTime();
        try {
            try (Connection connection = dataSource.getConnection()) {
                if (operation.type() == OperationType.DELETE) {
                    delete(connection, operation);
                    return;
                }
                upsert(connection, operation);
            }
        } finally {
            recordPostgresWrite(startedAt, operation.observationTag());
        }
    }

    @Override
    public void flushBatch(List<QueuedWriteOperation> operations) throws SQLException {
        long startedAt = System.nanoTime();
        try {
            flushBatchInternal(operations);
        } finally {
            if (!operations.isEmpty()) {
                recordPostgresWrite(startedAt, dominantObservationTag(operations));
            }
        }
    }

    private void flushBatchInternal(List<QueuedWriteOperation> operations) throws SQLException {
        if (operations.isEmpty()) {
            return;
        }
        List<QueuedWriteOperation> batchOperations = config.entityFlushPolicies().isEmpty()
                ? operations
                : normalizeOperations(operations);
        if (batchOperations.size() == 1) {
            flush(batchOperations.get(0));
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (config.entityFlushPolicies().isEmpty()) {
                    LinkedHashMap<StatementKey, List<QueuedWriteOperation>> groups = new LinkedHashMap<>();
                    for (QueuedWriteOperation operation : batchOperations) {
                        groups.computeIfAbsent(StatementKey.of(operation), ignored -> new ArrayList<>()).add(operation);
                    }
                    FlushExecutionPolicy defaultPolicy = defaultFlushExecutionPolicy();
                    for (Map.Entry<StatementKey, List<QueuedWriteOperation>> group : groups.entrySet()) {
                        if (group.getKey().delete()) {
                            flushDeleteBatch(connection, group.getValue(), defaultPolicy);
                        } else {
                            flushUpsertBatch(connection, group.getValue(), defaultPolicy);
                        }
                    }
                } else {
                    LinkedHashMap<ExecutionKey, List<QueuedWriteOperation>> groups = new LinkedHashMap<>();
                    for (QueuedWriteOperation operation : batchOperations) {
                        groups.computeIfAbsent(ExecutionKey.of(operation, resolveFlushExecutionPolicy(operation)), ignored -> new ArrayList<>()).add(operation);
                    }
                    for (Map.Entry<ExecutionKey, List<QueuedWriteOperation>> group : groups.entrySet()) {
                        if (group.getKey().statementKey().delete()) {
                            flushDeleteBatch(connection, group.getValue(), group.getKey().flushPolicy());
                        } else {
                            flushUpsertBatch(connection, group.getValue(), group.getKey().flushPolicy());
                        }
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private void recordPostgresWrite(long startedAtNanos, String observationTag) {
        if (performanceCollector == null) {
            return;
        }
        long elapsedMicros = (System.nanoTime() - startedAtNanos) / 1_000L;
        String normalizedTag = observationTag == null ? "" : observationTag.trim();
        if (normalizedTag.isBlank()) {
            performanceCollector.recordPostgresWrite(elapsedMicros);
            return;
        }
        performanceCollector.recordPostgresWrite(normalizedTag, elapsedMicros);
    }

    private void delete(Connection connection, QueuedWriteOperation operation) throws SQLException {
        String sql = "DELETE FROM " + operation.tableName()
                + " WHERE " + operation.idColumn() + " = ?"
                + " AND (" + operation.versionColumn() + " IS NULL OR " + operation.versionColumn() + " <= ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, convertValue(operation.id(), columnType(operation, operation.idColumn())));
            statement.setLong(2, operation.version());
            statement.executeUpdate();
        }
    }

    private void flushDeleteBatch(Connection connection, List<QueuedWriteOperation> operations, FlushExecutionPolicy flushPolicy) throws SQLException {
        int maxBatchSize = effectiveMaxBatchSize(flushPolicy, operations.size());
        for (int start = 0; start < operations.size(); start += maxBatchSize) {
            int end = Math.min(operations.size(), start + maxBatchSize);
            List<QueuedWriteOperation> chunk = operations.subList(start, end);
            if (flushPolicy.preferCopy() && config.postgresCopyBulkLoadEnabled() && chunk.size() >= flushPolicy.copyThreshold()) {
                flushDeleteCopy(connection, chunk);
                continue;
            }
            if (flushPolicy.preferMultiRow() && config.postgresMultiRowFlushEnabled()) {
                flushDeleteMultiRow(connection, chunk, flushPolicy.statementRowLimit());
                continue;
            }
            QueuedWriteOperation template = chunk.get(0);
            String sql = "DELETE FROM " + template.tableName()
                    + " WHERE " + template.idColumn() + " = ?"
                    + " AND (" + template.versionColumn() + " IS NULL OR " + template.versionColumn() + " <= ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (QueuedWriteOperation operation : chunk) {
                    statement.setObject(1, convertValue(operation.id(), columnType(operation, operation.idColumn())));
                    statement.setLong(2, operation.version());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
    }

    private void upsert(Connection connection, QueuedWriteOperation operation) throws SQLException {
        List<Map.Entry<String, String>> entries = new ArrayList<>(operation.columns().entrySet());
        String sql = upsertSql(operation, entries);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindUpsert(statement, operation, entries);
            statement.executeUpdate();
        }
    }

    private void flushUpsertBatch(Connection connection, List<QueuedWriteOperation> operations, FlushExecutionPolicy flushPolicy) throws SQLException {
        int maxBatchSize = effectiveMaxBatchSize(flushPolicy, operations.size());
        for (int start = 0; start < operations.size(); start += maxBatchSize) {
            int end = Math.min(operations.size(), start + maxBatchSize);
            List<QueuedWriteOperation> chunk = operations.subList(start, end);
            for (List<QueuedWriteOperation> distinctChunk : partitionDistinctIdentityBatches(chunk, flushPolicy.statementRowLimit())) {
                if (flushPolicy.preferCopy() && config.postgresCopyBulkLoadEnabled() && distinctChunk.size() >= flushPolicy.copyThreshold()) {
                    flushUpsertCopy(connection, distinctChunk);
                    continue;
                }
                if (flushPolicy.preferMultiRow() && config.postgresMultiRowFlushEnabled()) {
                    flushUpsertMultiRow(connection, distinctChunk, flushPolicy.statementRowLimit());
                    continue;
                }
                flushUpsertPreparedBatch(connection, distinctChunk);
            }
        }
    }

    private void flushUpsertPreparedBatch(Connection connection, List<QueuedWriteOperation> operations) throws SQLException {
        QueuedWriteOperation template = operations.get(0);
        List<Map.Entry<String, String>> entries = new ArrayList<>(template.columns().entrySet());
        String sql = upsertSql(template, entries);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (QueuedWriteOperation operation : operations) {
                bindUpsert(statement, operation, entries);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void flushDeleteMultiRow(Connection connection, List<QueuedWriteOperation> operations, int rowLimitOverride) throws SQLException {
        int rowLimit = Math.max(1, rowLimitOverride);
        for (int start = 0; start < operations.size(); start += rowLimit) {
            int end = Math.min(operations.size(), start + rowLimit);
            List<QueuedWriteOperation> chunk = operations.subList(start, end);
            QueuedWriteOperation template = chunk.get(0);
            StringJoiner valuesJoiner = new StringJoiner(", ");
            for (int index = 0; index < chunk.size(); index++) {
                valuesJoiner.add("(?, ?)");
            }
            String sql = "DELETE FROM " + template.tableName() + " target USING (VALUES " + valuesJoiner + ") AS staged("
                    + template.idColumn() + ", " + template.versionColumn() + ") "
                    + "WHERE target." + template.idColumn() + " = staged." + template.idColumn()
                    + " AND (target." + template.versionColumn() + " IS NULL OR target." + template.versionColumn()
                    + " <= staged." + template.versionColumn() + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameterIndex = 1;
                for (QueuedWriteOperation operation : chunk) {
                    statement.setObject(parameterIndex++, convertValue(operation.id(), columnType(operation, operation.idColumn())));
                    statement.setLong(parameterIndex++, operation.version());
                }
                statement.executeUpdate();
            }
        }
    }

    private void flushUpsertMultiRow(Connection connection, List<QueuedWriteOperation> operations, int rowLimitOverride) throws SQLException {
        int rowLimit = Math.max(1, rowLimitOverride);
        for (int start = 0; start < operations.size(); start += rowLimit) {
            int end = Math.min(operations.size(), start + rowLimit);
            List<QueuedWriteOperation> chunk = operations.subList(start, end);
            QueuedWriteOperation template = chunk.get(0);
            List<Map.Entry<String, String>> entries = new ArrayList<>(template.columns().entrySet());
            String sql = upsertMultiRowSql(template, entries, chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameterIndex = 1;
                for (QueuedWriteOperation operation : chunk) {
                    List<String> values = orderedColumnValues(operation, entries);
                    for (int valueIndex = 0; valueIndex < values.size(); valueIndex++) {
                        statement.setObject(
                                parameterIndex++,
                                convertValue(values.get(valueIndex), columnType(operation, entries.get(valueIndex).getKey()))
                        );
                    }
                }
                statement.executeUpdate();
            }
        }
    }

    static List<List<QueuedWriteOperation>> partitionDistinctIdentityBatches(List<QueuedWriteOperation> operations, int rowLimitOverride) {
        int rowLimit = Math.max(1, rowLimitOverride);
        ArrayList<List<QueuedWriteOperation>> partitions = new ArrayList<>();
        ArrayList<QueuedWriteOperation> current = new ArrayList<>(Math.min(rowLimit, operations.size()));
        LinkedHashSet<EntityOperationKey> seenKeys = new LinkedHashSet<>();
        for (QueuedWriteOperation operation : operations) {
            EntityOperationKey key = EntityOperationKey.of(operation);
            if (!current.isEmpty() && (current.size() >= rowLimit || seenKeys.contains(key))) {
                partitions.add(List.copyOf(current));
                current.clear();
                seenKeys.clear();
            }
            current.add(operation);
            seenKeys.add(key);
        }
        if (!current.isEmpty()) {
            partitions.add(List.copyOf(current));
        }
        return partitions;
    }

    private void flushDeleteCopy(Connection connection, List<QueuedWriteOperation> operations) throws SQLException {
        QueuedWriteOperation template = operations.get(0);
        String stagingTable = tempTableName("delete_stage");
        try (PreparedStatement createStatement = connection.prepareStatement(
                "CREATE TEMP TABLE " + stagingTable + " (" + template.idColumn() + " TEXT, " + template.versionColumn() + " BIGINT) ON COMMIT DROP")) {
            createStatement.executeUpdate();
        }
        copyCsv(
                connection,
                stagingTable,
                List.of(template.idColumn(), template.versionColumn()),
                operations.stream()
                        .map(operation -> List.of(operation.id(), String.valueOf(operation.version())))
                        .toList()
        );
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + template.tableName() + " target USING " + stagingTable + " staged "
                        + "WHERE target." + template.idColumn() + " = CAST(staged." + template.idColumn() + " AS "
                        + sqlCastType(template, template.idColumn()) + ") "
                        + "AND (target." + template.versionColumn() + " IS NULL OR target." + template.versionColumn()
                        + " <= staged." + template.versionColumn() + ")")) {
            statement.executeUpdate();
        }
    }

    private void flushUpsertCopy(Connection connection, List<QueuedWriteOperation> operations) throws SQLException {
        QueuedWriteOperation template = operations.get(0);
        String stagingTable = tempTableName("upsert_stage");
        try (PreparedStatement createStatement = connection.prepareStatement(
                "CREATE TEMP TABLE " + stagingTable + " (LIKE " + template.tableName() + " INCLUDING DEFAULTS) ON COMMIT DROP")) {
            createStatement.executeUpdate();
        }
        List<String> columns = new ArrayList<>(template.columns().keySet());
        copyCsv(
                connection,
                stagingTable,
                columns,
                operations.stream()
                        .map(operation -> columns.stream().map(column -> operation.columns().get(column)).toList())
                        .toList()
        );
        StringJoiner insertColumns = new StringJoiner(", ");
        StringJoiner updateSet = new StringJoiner(", ");
        for (String column : columns) {
            insertColumns.add(column);
            if (!column.equals(template.idColumn())) {
                updateSet.add(column + " = EXCLUDED." + column);
            }
        }
        String sql = "INSERT INTO " + template.tableName() + " (" + insertColumns + ") SELECT " + insertColumns + " FROM " + stagingTable
                + " ON CONFLICT (" + template.idColumn() + ") DO UPDATE SET " + updateSet
                + " WHERE " + template.tableName() + "." + template.versionColumn() + " IS NULL OR EXCLUDED."
                + template.versionColumn() + " > " + template.tableName() + "." + template.versionColumn();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private String upsertSql(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries) {
        StringJoiner insertColumns = new StringJoiner(", ");
        StringJoiner insertValues = new StringJoiner(", ");
        StringJoiner updateSet = new StringJoiner(", ");

        for (Map.Entry<String, String> entry : entries) {
            insertColumns.add(entry.getKey());
            insertValues.add("?");
            if (!entry.getKey().equals(operation.idColumn())) {
                updateSet.add(entry.getKey() + " = EXCLUDED." + entry.getKey());
            }
        }

        return "INSERT INTO " + operation.tableName()
                + " (" + insertColumns + ") VALUES (" + insertValues + ") "
                + "ON CONFLICT (" + operation.idColumn() + ") DO UPDATE SET " + updateSet
                + " WHERE " + operation.tableName() + "." + operation.versionColumn()
                + " IS NULL OR EXCLUDED." + operation.versionColumn()
                + " > " + operation.tableName() + "." + operation.versionColumn();
    }

    private String upsertMultiRowSql(
            QueuedWriteOperation operation,
            List<Map.Entry<String, String>> entries,
            int rowCount
    ) {
        StringJoiner insertColumns = new StringJoiner(", ");
        StringJoiner valuesRows = new StringJoiner(", ");
        StringJoiner updateSet = new StringJoiner(", ");

        for (Map.Entry<String, String> entry : entries) {
            insertColumns.add(entry.getKey());
            if (!entry.getKey().equals(operation.idColumn())) {
                updateSet.add(entry.getKey() + " = EXCLUDED." + entry.getKey());
            }
        }
        for (int row = 0; row < rowCount; row++) {
            StringJoiner rowValues = new StringJoiner(", ", "(", ")");
            for (int index = 0; index < entries.size(); index++) {
                rowValues.add("?");
            }
            valuesRows.add(rowValues.toString());
        }

        return "INSERT INTO " + operation.tableName()
                + " (" + insertColumns + ") VALUES " + valuesRows
                + " ON CONFLICT (" + operation.idColumn() + ") DO UPDATE SET " + updateSet
                + " WHERE " + operation.tableName() + "." + operation.versionColumn()
                + " IS NULL OR EXCLUDED." + operation.versionColumn()
                + " > " + operation.tableName() + "." + operation.versionColumn();
    }

    private void bindUpsert(
            PreparedStatement statement,
            QueuedWriteOperation operation,
            List<Map.Entry<String, String>> entries
    ) throws SQLException {
        List<String> values = orderedColumnValues(operation, entries);
        for (int i = 0; i < values.size(); i++) {
            statement.setObject(
                    i + 1,
                    convertValue(values.get(i), columnType(operation, entries.get(i).getKey()))
            );
        }
    }

    static String columnValue(QueuedWriteOperation operation, String columnName) {
        return operation.columns().get(columnName);
    }

    static List<String> orderedColumnValues(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries) {
        ArrayList<String> values = new ArrayList<>(entries.size());
        for (Map.Entry<String, String> entry : entries) {
            values.add(columnValue(operation, entry.getKey()));
        }
        return List.copyOf(values);
    }

    private String columnType(QueuedWriteOperation operation, String columnName) {
        if (operation.versionColumn() != null && operation.versionColumn().equals(columnName)) {
            return "java.lang.Long";
        }
        return entityRegistry.find(operation.entityName())
                .map(binding -> binding.metadata().columnTypes().get(columnName))
                .orElse("java.lang.String");
    }

    private String sqlCastType(QueuedWriteOperation operation, String columnName) {
        return switch (columnType(operation, columnName)) {
            case "int", "java.lang.Integer" -> "INTEGER";
            case "long", "java.lang.Long" -> "BIGINT";
            case "boolean", "java.lang.Boolean" -> "BOOLEAN";
            case "double", "java.lang.Double" -> "DOUBLE PRECISION";
            case "float", "java.lang.Float" -> "REAL";
            case "short", "java.lang.Short" -> "SMALLINT";
            case "byte", "java.lang.Byte" -> "SMALLINT";
            default -> "TEXT";
        };
    }

    private void copyCsv(
            Connection connection,
            String stagingTable,
            List<String> columns,
            List<List<String>> rows
    ) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        CopyManager copyManager = new CopyManager(connection.unwrap(BaseConnection.class));
        StringBuilder csv = new StringBuilder(rows.size() * Math.max(32, columns.size() * 12));
        for (List<String> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) {
                    csv.append(',');
                }
                appendCsvValue(csv, row.get(index));
            }
            csv.append('\n');
        }
        String copySql = "COPY " + stagingTable + " (" + String.join(", ", columns)
                + ") FROM STDIN WITH (FORMAT csv, NULL '\\N')";
        try {
            copyManager.copyIn(copySql, new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            if (exception instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("COPY failed for staging table " + stagingTable, exception);
        }
    }

    private void appendCsvValue(StringBuilder builder, String value) {
        if (value == null) {
            builder.append("\\N");
            return;
        }
        boolean quote = value.contains(",")
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r")
                || "\\N".equals(value);
        if (!quote) {
            builder.append(value);
            return;
        }
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"') {
                builder.append("\"\"");
            } else {
                builder.append(character);
            }
        }
        builder.append('"');
    }

    private String tempTableName(String prefix) {
        return prefix + "_" + Long.toUnsignedString(System.nanoTime(), 36);
    }

    private List<QueuedWriteOperation> normalizeOperations(List<QueuedWriteOperation> operations) {
        if (operations.size() < 4) {
            return operations;
        }
        ArrayList<QueuedWriteOperation> passthrough = new ArrayList<>(operations.size());
        LinkedHashMap<EntityOperationKey, QueuedWriteOperation> compacted = new LinkedHashMap<>();
        for (QueuedWriteOperation operation : operations) {
            FlushExecutionPolicy flushPolicy = resolveFlushExecutionPolicy(operation);
            if (!flushPolicy.stateCompactionEnabled()) {
                passthrough.add(operation);
                continue;
            }
            EntityOperationKey key = EntityOperationKey.of(operation);
            QueuedWriteOperation current = compacted.get(key);
            if (current == null || operation.version() >= current.version()) {
                compacted.put(key, operation);
            }
        }
        if (compacted.isEmpty()) {
            return operations;
        }
        ArrayList<QueuedWriteOperation> normalized = new ArrayList<>(passthrough.size() + compacted.size());
        normalized.addAll(passthrough);
        normalized.addAll(compacted.values());
        return normalized;
    }

    private int effectiveMaxBatchSize(FlushExecutionPolicy flushPolicy, int availableSize) {
        int configured = flushPolicy.maxBatchSize() > 0 ? flushPolicy.maxBatchSize() : availableSize;
        return Math.max(1, configured);
    }

    private FlushExecutionPolicy defaultFlushExecutionPolicy() {
        return new FlushExecutionPolicy(
                false,
                PersistenceSemantics.EXACT_SEQUENCE,
                config.postgresCopyBulkLoadEnabled(),
                config.postgresMultiRowFlushEnabled(),
                config.maxFlushBatchSize(),
                config.postgresMultiRowStatementRowLimit(),
                config.postgresCopyThreshold()
        );
    }

    private FlushExecutionPolicy resolveFlushExecutionPolicy(QueuedWriteOperation operation) {
        EntityFlushPolicy matchedPolicy = config.entityFlushPolicies().stream()
                .filter(policy -> policy.matches(operation))
                .max(Comparator.comparingInt(EntityFlushPolicy::specificity))
                .orElse(null);
        if (matchedPolicy == null) {
            return defaultFlushExecutionPolicy();
        }
        return new FlushExecutionPolicy(
                matchedPolicy.effectivePersistenceSemantics().stateCompactionEnabled(),
                matchedPolicy.effectivePersistenceSemantics(),
                matchedPolicy.preferCopy(),
                matchedPolicy.preferMultiRow(),
                matchedPolicy.maxBatchSize() > 0 ? matchedPolicy.maxBatchSize() : config.maxFlushBatchSize(),
                matchedPolicy.statementRowLimit() > 0 ? matchedPolicy.statementRowLimit() : config.postgresMultiRowStatementRowLimit(),
                matchedPolicy.copyThreshold() > 0 ? matchedPolicy.copyThreshold() : config.postgresCopyThreshold()
        );
    }

    private Object convertValue(String value, String typeName) {
        if (value == null) {
            return null;
        }

        return switch (typeName) {
            case "java.lang.String" -> value;
            case "int", "java.lang.Integer" -> Integer.valueOf(value);
            case "long", "java.lang.Long" -> Long.valueOf(value);
            case "boolean", "java.lang.Boolean" -> Boolean.valueOf(value);
            case "double", "java.lang.Double" -> Double.valueOf(value);
            case "float", "java.lang.Float" -> Float.valueOf(value);
            case "short", "java.lang.Short" -> Short.valueOf(value);
            case "byte", "java.lang.Byte" -> Byte.valueOf(value);
            case "java.time.Instant" -> Instant.parse(value);
            case "java.time.LocalDate" -> LocalDate.parse(value);
            case "java.time.LocalDateTime" -> LocalDateTime.parse(value);
            case "java.time.OffsetDateTime" -> OffsetDateTime.parse(value);
            default -> value;
        };
    }

    @Override
    public WriteFailureDetails classify(Exception exception) {
        return failureClassifier.classify(exception);
    }

    private String dominantObservationTag(List<QueuedWriteOperation> operations) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (QueuedWriteOperation operation : operations) {
            String tag = operation.observationTag();
            if (tag == null || tag.isBlank()) {
                continue;
            }
            counts.merge(tag.trim(), 1, Integer::sum);
        }
        String bestTag = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestTag = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestTag;
    }

    private record StatementKey(
            OperationType type,
            String tableName,
            String observationTag,
            String idColumn,
            String versionColumn,
            List<String> columns,
            boolean delete
    ) {
        private static StatementKey of(QueuedWriteOperation operation) {
            return new StatementKey(
                    operation.type(),
                    operation.tableName(),
                    operation.observationTag(),
                    operation.idColumn(),
                    operation.versionColumn(),
                    List.copyOf(operation.columns().keySet()),
                    operation.type() == OperationType.DELETE
            );
        }
    }

    private record ExecutionKey(
            StatementKey statementKey,
            FlushExecutionPolicy flushPolicy
    ) {
        private static ExecutionKey of(QueuedWriteOperation operation, FlushExecutionPolicy flushPolicy) {
            return new ExecutionKey(StatementKey.of(operation), flushPolicy);
        }
    }

    private record FlushExecutionPolicy(
            boolean stateCompactionEnabled,
            PersistenceSemantics persistenceSemantics,
            boolean preferCopy,
            boolean preferMultiRow,
            int maxBatchSize,
            int statementRowLimit,
            int copyThreshold
    ) {
    }

    private record EntityOperationKey(String tableName, String id) {
        private static EntityOperationKey of(QueuedWriteOperation operation) {
            return new EntityOperationKey(operation.tableName(), operation.id());
        }
    }
}
