package com.reactor.cachedb.mssql;

import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.FailureClassifyingFlusher;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteFailureDetails;
import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.jdbc.JdbcWriteBehindSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MssqlWriteBehindFlusher implements FailureClassifyingFlusher {

    private final DataSource dataSource;
    private final EntityRegistry entityRegistry;
    private final MssqlDatabaseDialect dialect;
    private final MssqlFailureClassifier failureClassifier;
    private final StoragePerformanceCollector performanceCollector;
    private final MssqlWriteBehindOptions options;
    private final int transactionBatchSize;

    public MssqlWriteBehindFlusher(DataSource dataSource, EntityRegistry entityRegistry) {
        this(dataSource, entityRegistry, WriteBehindConfig.defaults(), (StoragePerformanceCollector) null);
    }

    public MssqlWriteBehindFlusher(DataSource dataSource, EntityRegistry entityRegistry, WriteBehindConfig config) {
        this(dataSource, entityRegistry, config, (StoragePerformanceCollector) null);
    }

    public MssqlWriteBehindFlusher(
            DataSource dataSource,
            EntityRegistry entityRegistry,
            WriteBehindConfig config,
            StoragePerformanceCollector performanceCollector
    ) {
        this(dataSource, entityRegistry, config, performanceCollector, MssqlWriteBehindOptions.defaults());
    }

    public MssqlWriteBehindFlusher(
            DataSource dataSource,
            EntityRegistry entityRegistry,
            WriteBehindConfig config,
            MssqlWriteBehindOptions options
    ) {
        this(dataSource, entityRegistry, config, null, options);
    }

    public MssqlWriteBehindFlusher(
            DataSource dataSource,
            EntityRegistry entityRegistry,
            WriteBehindConfig config,
            StoragePerformanceCollector performanceCollector,
            MssqlWriteBehindOptions options
    ) {
        this.dataSource = dataSource;
        this.entityRegistry = entityRegistry;
        this.dialect = new MssqlDatabaseDialect();
        this.failureClassifier = new MssqlFailureClassifier();
        this.performanceCollector = performanceCollector;
        this.options = options == null ? MssqlWriteBehindOptions.defaults() : options;
        this.transactionBatchSize = Math.max(1, config == null
                ? WriteBehindConfig.defaults().maxFlushBatchSize()
                : config.maxFlushBatchSize());
    }

    public static WriteBehindFlusherFactory factory(MssqlWriteBehindOptions options) {
        MssqlWriteBehindOptions resolvedOptions = options == null ? MssqlWriteBehindOptions.defaults() : options;
        return (dataSource, entityRegistry, writeBehindConfig, performanceCollector) ->
                new MssqlWriteBehindFlusher(
                        dataSource,
                        entityRegistry,
                        writeBehindConfig,
                        performanceCollector,
                        resolvedOptions
                );
    }

    @Override
    public void flush(QueuedWriteOperation operation) throws SQLException {
        long startedAt = System.nanoTime();
        try {
            try (Connection connection = dataSource.getConnection()) {
                executeInTransaction(connection, List.of(operation));
            }
        } finally {
            recordWrite(startedAt, operation.observationTag());
        }
    }

    @Override
    public void flushBatch(List<QueuedWriteOperation> operations) throws SQLException {
        long startedAt = System.nanoTime();
        try {
            if (operations.isEmpty()) {
                return;
            }
            try (Connection connection = dataSource.getConnection()) {
                for (int start = 0; start < operations.size(); start += transactionBatchSize) {
                    int end = Math.min(operations.size(), start + transactionBatchSize);
                    executeInTransaction(connection, operations.subList(start, end));
                }
            }
        } finally {
            if (!operations.isEmpty()) {
                recordWrite(startedAt, dominantObservationTag(operations));
            }
        }
    }

    private void executeInTransaction(Connection connection, List<QueuedWriteOperation> operations) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        int previousIsolation = connection.getTransactionIsolation();
        int previousLockTimeout = MssqlWriteBehindOptions.LOCK_TIMEOUT_WAIT_FOREVER;
        boolean restoreLockTimeout = false;
        connection.setAutoCommit(false);
        try {
            if (options.lockTimeoutEnabled()) {
                if (options.restoreLockTimeoutAfterTransaction()) {
                    previousLockTimeout = currentLockTimeout(connection);
                    restoreLockTimeout = true;
                }
                setLockTimeout(connection, options.lockTimeoutMillis());
            }
            connection.setTransactionIsolation(options.transactionIsolation());
            for (QueuedWriteOperation operation : operations) {
                if (operation.type() == OperationType.DELETE) {
                    delete(connection, operation);
                } else {
                    upsert(connection, operation);
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            SQLException restoreFailure = null;
            try {
                connection.setTransactionIsolation(previousIsolation);
            } catch (SQLException exception) {
                restoreFailure = exception;
            }
            if (restoreLockTimeout) {
                try {
                    setLockTimeout(connection, previousLockTimeout);
                } catch (SQLException exception) {
                    if (restoreFailure == null) {
                        restoreFailure = exception;
                    } else {
                        restoreFailure.addSuppressed(exception);
                    }
                }
            }
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException exception) {
                if (restoreFailure == null) {
                    restoreFailure = exception;
                } else {
                    restoreFailure.addSuppressed(exception);
                }
            }
            if (restoreFailure != null) {
                throw restoreFailure;
            }
        }
    }

    private void delete(Connection connection, QueuedWriteOperation operation) throws SQLException {
        try (PreparedStatement statement = prepareStatement(connection, dialect.deleteSql(operation))) {
            statement.setObject(1, JdbcWriteBehindSupport.convertValue(
                    operation.id(),
                    JdbcWriteBehindSupport.columnType(entityRegistry, operation, operation.idColumn())
            ));
            statement.setLong(2, operation.version());
            statement.executeUpdate();
        }
    }

    private void upsert(Connection connection, QueuedWriteOperation operation) throws SQLException {
        List<Map.Entry<String, String>> entries = new ArrayList<>(operation.columns().entrySet());
        int updated = update(connection, operation, entries);
        if (updated > 0) {
            return;
        }
        if (exists(connection, operation)) {
            return;
        }
        insert(connection, operation, entries);
    }

    private int update(
            Connection connection,
            QueuedWriteOperation operation,
            List<Map.Entry<String, String>> entries
    ) throws SQLException {
        try (PreparedStatement statement = prepareStatement(connection, dialect.updateSql(operation, entries))) {
            int parameterIndex = 1;
            for (Map.Entry<String, String> entry : entries) {
                if (entry.getKey().equals(operation.idColumn())) {
                    continue;
                }
                statement.setObject(parameterIndex++, JdbcWriteBehindSupport.convertValue(
                        entry.getValue(),
                        JdbcWriteBehindSupport.columnType(entityRegistry, operation, entry.getKey())
                ));
            }
            statement.setObject(parameterIndex++, JdbcWriteBehindSupport.convertValue(
                    operation.id(),
                    JdbcWriteBehindSupport.columnType(entityRegistry, operation, operation.idColumn())
            ));
            statement.setLong(parameterIndex, operation.version());
            return statement.executeUpdate();
        }
    }

    private boolean exists(Connection connection, QueuedWriteOperation operation) throws SQLException {
        try (PreparedStatement statement = prepareStatement(connection, dialect.existsSql(operation))) {
            statement.setObject(1, JdbcWriteBehindSupport.convertValue(
                    operation.id(),
                    JdbcWriteBehindSupport.columnType(entityRegistry, operation, operation.idColumn())
            ));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void insert(
            Connection connection,
            QueuedWriteOperation operation,
            List<Map.Entry<String, String>> entries
    ) throws SQLException {
        try (PreparedStatement statement = prepareStatement(connection, dialect.insertSql(operation, entries))) {
            JdbcWriteBehindSupport.bindUpsert(statement, operation, entityRegistry, entries);
            statement.executeUpdate();
        }
    }

    private PreparedStatement prepareStatement(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        applyQueryTimeout(statement);
        return statement;
    }

    private int currentLockTimeout(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            applyQueryTimeout(statement);
            try (ResultSet resultSet = statement.executeQuery("SELECT @@LOCK_TIMEOUT")) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        }
        return MssqlWriteBehindOptions.LOCK_TIMEOUT_WAIT_FOREVER;
    }

    private void setLockTimeout(Connection connection, int lockTimeoutMillis) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            applyQueryTimeout(statement);
            statement.execute("SET LOCK_TIMEOUT " + lockTimeoutMillis);
        }
    }

    private void applyQueryTimeout(Statement statement) throws SQLException {
        if (options.queryTimeoutSeconds() > 0) {
            statement.setQueryTimeout(options.queryTimeoutSeconds());
        }
    }

    private void recordWrite(long startedAtNanos, String observationTag) {
        if (performanceCollector == null) {
            return;
        }
        long elapsedMicros = (System.nanoTime() - startedAtNanos) / 1_000L;
        String normalizedTag = observationTag == null ? "" : observationTag.trim();
        if (normalizedTag.isBlank()) {
            performanceCollector.recordPostgresWrite("mssql:write-behind", elapsedMicros);
            return;
        }
        performanceCollector.recordPostgresWrite(providerTag(normalizedTag), elapsedMicros);
    }

    private String providerTag(String normalizedTag) {
        if (normalizedTag.regionMatches(true, 0, "mssql:", 0, "mssql:".length())) {
            return normalizedTag;
        }
        return "mssql:" + normalizedTag;
    }

    private String dominantObservationTag(List<QueuedWriteOperation> operations) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
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

    @Override
    public WriteFailureDetails classify(Exception exception) {
        return failureClassifier.classify(exception);
    }
}
