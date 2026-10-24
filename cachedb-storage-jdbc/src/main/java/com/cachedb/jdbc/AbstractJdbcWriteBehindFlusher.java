package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.queue.FailureClassifyingFlusher;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.EntityRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;

/** Shared transaction boundary; dialects retain their batching, ordering and retry policies. */
public abstract class AbstractJdbcWriteBehindFlusher implements FailureClassifyingFlusher {
    protected final DataSource dataSource;
    protected final EntityRegistry entityRegistry;
    protected final JdbcWriteFence writeFence;
    private final com.reactor.cachedb.core.config.WriteBehindConfig flushConfig;
    private final JdbcSchemaDialect columnDialect;

    protected AbstractJdbcWriteBehindFlusher(
            DataSource dataSource, EntityRegistry entityRegistry,
            JdbcWriteFence.Provider provider, int timeoutSeconds) {
        this(dataSource, entityRegistry, provider, timeoutSeconds, null);
    }

    protected AbstractJdbcWriteBehindFlusher(DataSource dataSource, EntityRegistry entityRegistry,
            JdbcWriteFence.Provider provider, int timeoutSeconds,
            com.reactor.cachedb.core.config.WriteBehindConfig config) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.entityRegistry = entityRegistry;
        this.writeFence = new JdbcWriteFence(dataSource, entityRegistry, provider, timeoutSeconds);
        this.flushConfig = config;
        this.columnDialect = switch (provider) {
            case POSTGRES -> JdbcSchemaDialects.postgres();
            case MSSQL -> JdbcSchemaDialects.mssql();
            case ORACLE -> JdbcSchemaDialects.oracle();
        };
    }

    /** Existing external subclasses retain the family-based column guard by default. */
    protected JdbcDatabaseDialect dialectForValidation() { return null; }

    private void validateWriteShapes(Connection connection, List<QueuedWriteOperation> operations) throws SQLException {
        JdbcDatabaseDialect dialect = dialectForValidation();
        int maxColumns = dialect == null
                ? columnDialect.resolveMaxColumnsInTable(connection.getMetaData())
                : dialect.resolveMaxColumnsInTable(connection.getMetaData());
        for (QueuedWriteOperation operation : operations) {
            if (operation.atomicBatch()) throw new IllegalArgumentException("Atomic envelopes must be expanded before JDBC flush");
            var columns = operation.columns();
            JdbcColumnLimits.validate(columns.size(), maxColumns, columnDialect.name());
            if (operation.type() == com.reactor.cachedb.core.model.OperationType.DELETE) {
                if (dialect != null) dialect.safeStatementRowLimit(1, 2);
                continue;
            }
            int physicalColumns = columns.size();
            String id = operation.idColumn();
            String version = operation.versionColumn();
            String deleted = operation.deletedColumn();
            if (!columns.containsKey(id)) physicalColumns++;
            if (version != null && !version.isBlank() && !version.equals(id) && !columns.containsKey(version)) physicalColumns++;
            if (deleted != null && !deleted.isBlank() && !deleted.equals(id) && !deleted.equals(version)
                    && !columns.containsKey(deleted)) physicalColumns++;
            JdbcColumnLimits.validate(physicalColumns, maxColumns, columnDialect.name());
            if (dialect != null) dialect.safeStatementRowLimit(1, dialect.upsertParameterCount(operation));
        }
    }

    @FunctionalInterface
    protected interface BatchWriter {
        void write(List<QueuedWriteOperation> operations) throws SQLException;
    }

    protected interface SessionSettings {
        void apply(Connection connection) throws SQLException;
        void restore(Connection connection) throws SQLException;
    }

    protected final void inTransaction(
            Connection connection, List<QueuedWriteOperation> operations, Integer isolation,
            SessionSettings settings, BatchWriter writer) throws SQLException {
        // Reject an incompatible batch before changing the connection or its durable write fence.
        validateWriteShapes(connection, operations);
        boolean autoCommit = connection.getAutoCommit();
        int previousIsolation = isolation == null ? 0 : connection.getTransactionIsolation();
        Throwable primary = null;
        connection.setAutoCommit(false);
        try {
            if (settings != null) settings.apply(connection);
            if (isolation != null) connection.setTransactionIsolation(isolation);
            writer.write(writeFence.filter(connection, operations, coalesceWrites(operations)));
            connection.commit();
        } catch (SQLException | RuntimeException | Error failure) {
            primary = failure;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            SQLException restoration = null;
            if (isolation != null) {
                try {
                    connection.setTransactionIsolation(previousIsolation);
                } catch (SQLException failure) {
                    restoration = failure;
                }
            }
            if (settings != null) {
                try {
                    settings.restore(connection);
                } catch (SQLException failure) {
                    if (restoration == null) restoration = failure;
                    else restoration.addSuppressed(failure);
                }
            }
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException failure) {
                if (restoration == null) restoration = failure;
                else restoration.addSuppressed(failure);
            }
            if (restoration != null) {
                if (primary == null) throw restoration;
                primary.addSuppressed(restoration);
            }
        }
    }

    protected boolean coalesceWrites(List<QueuedWriteOperation> operations) {
        if (flushConfig == null || flushConfig.entityFlushPolicies().isEmpty()) return false;
        for (QueuedWriteOperation operation : operations) {
            com.reactor.cachedb.core.config.EntityFlushPolicy selected = null;
            for (var policy : flushConfig.entityFlushPolicies()) {
                if (policy.matches(operation) && (selected == null || policy.specificity() > selected.specificity())) selected = policy;
            }
            if (selected == null || !selected.effectivePersistenceSemantics().stateCompactionEnabled()) return false;
        }
        return true;
    }

    protected static <K> LinkedHashMap<K, List<QueuedWriteOperation>> groupOperations(
            List<QueuedWriteOperation> operations, Function<QueuedWriteOperation, K> key) {
        LinkedHashMap<K, List<QueuedWriteOperation>> groups = new LinkedHashMap<>();
        for (QueuedWriteOperation operation : operations) {
            groups.computeIfAbsent(key.apply(operation), ignored -> new ArrayList<>()).add(operation);
        }
        return groups;
    }
}
