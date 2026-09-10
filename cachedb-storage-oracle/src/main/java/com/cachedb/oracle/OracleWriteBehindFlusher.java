package com.reactor.cachedb.oracle;

import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.FailureClassifyingFlusher;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;
import com.reactor.cachedb.core.queue.WriteFailureDetails;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.jdbc.JdbcWriteBehindSupport;
import com.reactor.cachedb.jdbc.VersionGuardedWriteSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OracleWriteBehindFlusher implements FailureClassifyingFlusher {
    private static final int ORA_UNIQUE_CONSTRAINT = 1;

    private final DataSource dataSource;
    private final EntityRegistry entityRegistry;
    private final OracleDatabaseDialect dialect = new OracleDatabaseDialect();
    private final OracleFailureClassifier failureClassifier = new OracleFailureClassifier();
    private final StoragePerformanceCollector performanceCollector;
    private final OracleWriteBehindOptions options;
    private final int transactionBatchSize;

    public OracleWriteBehindFlusher(DataSource dataSource, EntityRegistry entityRegistry) {
        this(dataSource, entityRegistry, WriteBehindConfig.defaults(), null, OracleWriteBehindOptions.defaults());
    }

    public OracleWriteBehindFlusher(
            DataSource dataSource,
            EntityRegistry entityRegistry,
            WriteBehindConfig config
    ) {
        this(dataSource, entityRegistry, config, null, OracleWriteBehindOptions.defaults());
    }

    public OracleWriteBehindFlusher(
            DataSource dataSource,
            EntityRegistry entityRegistry,
            WriteBehindConfig config,
            OracleWriteBehindOptions options
    ) {
        this(dataSource, entityRegistry, config, null, options);
    }

    public OracleWriteBehindFlusher(
            DataSource dataSource,
            EntityRegistry entityRegistry,
            WriteBehindConfig config,
            StoragePerformanceCollector performanceCollector,
            OracleWriteBehindOptions options
    ) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.entityRegistry = java.util.Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.performanceCollector = performanceCollector;
        this.options = options == null ? OracleWriteBehindOptions.defaults() : options;
        WriteBehindConfig resolvedConfig = config == null ? WriteBehindConfig.defaults() : config;
        this.transactionBatchSize = Math.max(1, resolvedConfig.maxFlushBatchSize());
    }

    public static WriteBehindFlusherFactory factory(OracleWriteBehindOptions options) {
        OracleWriteBehindOptions resolved = options == null ? OracleWriteBehindOptions.defaults() : options;
        return (dataSource, entityRegistry, config, collector) ->
                new OracleWriteBehindFlusher(dataSource, entityRegistry, config, collector, resolved);
    }

    @Override
    public void flush(QueuedWriteOperation operation) throws SQLException {
        long startedAt = System.nanoTime();
        try {
            executeWithDuplicateRecovery(List.of(operation), options.duplicateRaceRetries());
        } finally {
            recordWrite(startedAt, operation.observationTag());
        }
    }

    @Override
    public void flushBatch(List<QueuedWriteOperation> operations) throws SQLException {
        long startedAt = System.nanoTime();
        try {
            for (int start = 0; start < operations.size(); start += transactionBatchSize) {
                int end = Math.min(operations.size(), start + transactionBatchSize);
                executeWithDuplicateRecovery(operations.subList(start, end), options.duplicateRaceRetries());
            }
        } finally {
            if (!operations.isEmpty()) {
                recordWrite(startedAt, dominantObservationTag(operations));
            }
        }
    }

    private void executeWithDuplicateRecovery(List<QueuedWriteOperation> operations, int retriesRemaining)
            throws SQLException {
        if (operations.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            executeInTransaction(connection, operations);
        } catch (SQLException failure) {
            if (!isUniqueConstraintViolation(failure) || retriesRemaining <= 0) {
                throw failure;
            }
            if (operations.size() == 1) {
                QueuedWriteOperation operation = operations.get(0);
                if (operation.type() == OperationType.DELETE) {
                    throw failure;
                }
                Long currentVersion = currentVersion(operation);
                if (currentVersion != null && currentVersion >= operation.version()) {
                    return;
                }
                if (currentVersion == null) {
                    throw failure;
                }
                executeWithDuplicateRecovery(operations, retriesRemaining - 1);
                return;
            }

            // Oracle stops a JDBC batch at the first failure. The transaction is rolled back,
            // then each operation is replayed independently so a rare insert race does not
            // degrade the normal batched path.
            for (QueuedWriteOperation operation : operations) {
                executeWithDuplicateRecovery(List.of(operation), retriesRemaining - 1);
            }
        }
    }

    private void executeInTransaction(Connection connection, List<QueuedWriteOperation> operations) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        int previousIsolation = connection.getTransactionIsolation();
        Exception primaryFailure = null;
        connection.setAutoCommit(false);
        try {
            connection.setTransactionIsolation(options.transactionIsolation());
            LinkedHashMap<StatementKey, List<QueuedWriteOperation>> groups = new LinkedHashMap<>();
            for (QueuedWriteOperation operation : operations) {
                StatementKey key = StatementKey.of(operation, dialect);
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(operation);
            }
            for (Map.Entry<StatementKey, List<QueuedWriteOperation>> group : groups.entrySet()) {
                executeGroup(connection, group.getKey(), group.getValue());
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            primaryFailure = failure;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            try {
                restoreConnection(connection, previousIsolation, previousAutoCommit);
            } catch (SQLException restoreFailure) {
                if (primaryFailure == null) {
                    throw restoreFailure;
                }
                primaryFailure.addSuppressed(restoreFailure);
            }
        }
    }

    private void executeGroup(
            Connection connection,
            StatementKey key,
            List<QueuedWriteOperation> operations
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(key.sql())) {
            statement.setQueryTimeout(options.queryTimeoutSeconds());
            for (QueuedWriteOperation operation : operations) {
                statement.clearParameters();
                if (operation.type() == OperationType.DELETE) {
                    bindDelete(statement, operation);
                } else {
                    bindUpsert(statement, operation, entries(operation));
                }
                statement.addBatch();
            }
            int[] outcomes = statement.executeBatch();
            statement.clearBatch();
            VersionGuardedWriteSupport.verifyBatchOutcome(
                    connection,
                    entityRegistry,
                    operations,
                    outcomes,
                    options.queryTimeoutSeconds()
            );
        }
    }

    private void bindDelete(PreparedStatement statement, QueuedWriteOperation operation) throws SQLException {
        OracleValueBinder.bind(
                statement,
                1,
                operation.id(),
                columnType(operation, operation.idColumn()),
                options.emptyStringPolicy()
        );
        statement.setLong(2, operation.version());
    }

    private void bindUpsert(
            PreparedStatement statement,
            QueuedWriteOperation operation,
            List<Map.Entry<String, String>> entries
    ) throws SQLException {
        int parameterIndex = 1;
        for (Map.Entry<String, String> entry : entries) {
            OracleValueBinder.bind(
                    statement,
                    parameterIndex++,
                    entry.getValue(),
                    columnType(operation, entry.getKey()),
                    options.emptyStringPolicy()
            );
        }
    }

    private List<Map.Entry<String, String>> entries(QueuedWriteOperation operation) {
        return new ArrayList<>(operation.columns().entrySet());
    }

    private String columnType(QueuedWriteOperation operation, String columnName) {
        return JdbcWriteBehindSupport.columnType(entityRegistry, operation, columnName);
    }

    private Long currentVersion(QueuedWriteOperation operation) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return VersionGuardedWriteSupport.currentVersion(
                    connection,
                    entityRegistry,
                    operation,
                    options.queryTimeoutSeconds()
            );
        }
    }

    private void restoreConnection(Connection connection, int previousIsolation, boolean previousAutoCommit)
            throws SQLException {
        SQLException failure = null;
        try {
            connection.setTransactionIsolation(previousIsolation);
        } catch (SQLException exception) {
            failure = exception;
        }
        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private boolean isUniqueConstraintViolation(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            if (Math.abs(current.getErrorCode()) == ORA_UNIQUE_CONSTRAINT) {
                return true;
            }
            current = current.getNextException();
        }
        Throwable cause = exception.getCause();
        return cause instanceof SQLException sqlCause && isUniqueConstraintViolation(sqlCause);
    }

    private void recordWrite(long startedAtNanos, String observationTag) {
        if (performanceCollector == null) {
            return;
        }
        long elapsedMicros = (System.nanoTime() - startedAtNanos) / 1_000L;
        String normalized = observationTag == null ? "" : observationTag.trim();
        performanceCollector.recordSqlWrite(
                normalized.isBlank() ? "oracle:write-behind" : providerTag(normalized),
                elapsedMicros
        );
    }

    private String providerTag(String tag) {
        return tag.regionMatches(true, 0, "oracle:", 0, "oracle:".length()) ? tag : "oracle:" + tag;
    }

    private String dominantObservationTag(List<QueuedWriteOperation> operations) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (QueuedWriteOperation operation : operations) {
            String tag = operation.observationTag() == null ? "" : operation.observationTag().trim();
            counts.merge(tag, 1, Integer::sum);
        }
        String dominant = "";
        int maximum = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > maximum) {
                dominant = entry.getKey();
                maximum = entry.getValue();
            }
        }
        return dominant;
    }

    @Override
    public WriteFailureDetails classify(Exception exception) {
        return failureClassifier.classify(exception);
    }

    private record StatementKey(OperationType type, String sql) {
        private static StatementKey of(QueuedWriteOperation operation, OracleDatabaseDialect dialect) {
            if (operation.type() == OperationType.DELETE) {
                return new StatementKey(operation.type(), dialect.deleteSql(operation));
            }
            List<Map.Entry<String, String>> entries = new ArrayList<>(operation.columns().entrySet());
            return new StatementKey(operation.type(), dialect.upsertSql(operation, entries));
        }
    }
}
