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
    private final int statementTimeoutSeconds;
    private final java.util.concurrent.atomic.AtomicLong reorderedBatches = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong reorderedOperations = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong reorderSkippedMixedOptIn = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.Set<String> reorderLoggedEntities = new java.util.HashSet<>();

    public long reorderedBatches() { return reorderedBatches.get(); }
    public long reorderedOperations() { return reorderedOperations.get(); }
    public long reorderSkippedMixedOptIn() { return reorderSkippedMixedOptIn.get(); }

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
        this.statementTimeoutSeconds = Math.max(1, timeoutSeconds);
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
            if (operation.type() == com.reactor.cachedb.core.model.OperationType.PATCH) {
                if (columns.isEmpty() || operation.versionColumn() == null || operation.versionColumn().isBlank())
                    throw new IllegalArgumentException("PATCH requires changed columns and a version column");
                if (dialect != null) dialect.safeStatementRowLimit(1, columns.size() + 3);
                continue;
            }
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
            boolean hasPatch = operations.stream().anyMatch(op -> op.type() == com.reactor.cachedb.core.model.OperationType.PATCH);
            writer.write(compactIndependentWrites(writeFence.filter(connection, operations, !hasPatch && coalesceWrites(operations))));
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

    private record Identity(String table, String id) {}
    private record Shape(Object type, String table, String entity, String id, String version, String deleted, List<String> columns) {}

    /** Linear bounded planning. Caller must establish independence across different physical keys. */
    protected static List<List<QueuedWriteOperation>> planOrderPreservingRuns(List<QueuedWriteOperation> operations) {
        if (operations.size() > 10_000) throw new IllegalArgumentException("Write planning limit exceeded: max 10000");
        var runs = new ArrayList<List<QueuedWriteOperation>>();
        var open = new java.util.HashMap<Shape, Integer>();
        var last = new java.util.HashMap<Identity, Integer>();
        int barrier = -1;
        for (var operation : operations) {
            Objects.requireNonNull(operation);
            if (operation.tableName() == null || operation.id() == null) {
                runs.add(new ArrayList<>(List.of(operation))); barrier = runs.size() - 1; open.clear(); continue;
            }
            var identity = new Identity(operation.tableName(), String.valueOf(operation.id()));
            var shape = new Shape(operation.type(), operation.tableName(), operation.entityName(), operation.idColumn(),
                    operation.versionColumn(), operation.deletedColumn(), List.copyOf(operation.columns().keySet()));
            int minimum = Math.max(barrier, last.getOrDefault(identity, -1));
            Integer index = open.get(shape);
            if (index == null || index <= minimum) {
                index = runs.size(); runs.add(new ArrayList<>()); open.put(shape, index);
            }
            runs.get(index).add(operation); last.put(identity, index);
        }
        return runs.stream().map(List::copyOf).toList();
    }

    final List<QueuedWriteOperation> compactIndependentWrites(List<QueuedWriteOperation> operations) {
        if (entityRegistry == null || operations.size() < 2) return operations;
        boolean optedIn = false, excluded = false;
        for (var operation : operations) {
            var binding = entityRegistry.find(operation.entityName());
            if (binding.isPresent() && binding.get().metadata().independentWriteReordering()) optedIn = true;
            else excluded = true;
        }
        if (excluded) {
            if (optedIn) reorderSkippedMixedOptIn.incrementAndGet();
            return operations;
        }
        var result = new ArrayList<QueuedWriteOperation>(operations.size());
        for (var run : planOrderPreservingRuns(operations)) result.addAll(run);
        reorderedBatches.incrementAndGet();
        reorderedOperations.addAndGet(operations.size());
        synchronized (reorderLoggedEntities) {
            for (var operation : operations) {
                String name = operation.entityName();
                if (name.length() > 512) name = name.substring(0, 512);
                if (reorderLoggedEntities.size() < 1024 && reorderLoggedEntities.add(name))
                    System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                            "Independent write regrouping enabled for " + name
                            + "; same-key order preserved; cross-row independence is the application's contract");
            }
        }
        return result;
    }

    protected final void patch(Connection connection, QueuedWriteOperation operation) throws SQLException {
        patchBatch(connection, List.of(operation));
    }

    /** Only consecutive, identical shapes may share a statement; causal ordering is retained. */
    protected final int patchConsecutiveBatch(Connection connection,
            List<QueuedWriteOperation> operations, int start) throws SQLException {
        var template = operations.get(start);
        int end = start + 1;
        while (end < operations.size() && sameWriteShape(template, operations.get(end))) end++;
        patchBatch(connection, operations.subList(start, end));
        return end;
    }

    protected static boolean sameWriteShape(QueuedWriteOperation left, QueuedWriteOperation right) {
        return left.type() == right.type()
                && Objects.equals(left.entityName(), right.entityName())
                && Objects.equals(left.tableName(), right.tableName())
                && Objects.equals(left.idColumn(), right.idColumn())
                && Objects.equals(left.versionColumn(), right.versionColumn())
                && Objects.equals(left.deletedColumn(), right.deletedColumn())
                && new ArrayList<>(left.columns().keySet()).equals(new ArrayList<>(right.columns().keySet()));
    }

    protected final void patchBatch(Connection connection, List<QueuedWriteOperation> operations) throws SQLException {
        if (operations.isEmpty()) return;
        QueuedWriteOperation operation = operations.get(0);
        var changes = new ArrayList<>(operation.columns().entrySet());
        changes.removeIf(entry -> entry.getKey().equals(operation.idColumn()) || entry.getKey().equals(operation.versionColumn()));
        if (changes.isEmpty()) throw new IllegalArgumentException("PATCH has no changed columns");
        String table = checkedIdentifier(operation.tableName(), true);
        String version = checkedIdentifier(operation.versionColumn(), false);
        String id = checkedIdentifier(operation.idColumn(), false);
        var sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        for (var change : changes) {
            if (change.getKey().equals(operation.deletedColumn()))
                throw new IllegalArgumentException("PATCH cannot change the deletion marker");
            sql.append(checkedIdentifier(change.getKey(), false)).append("=?,");
        }
        sql.append(version).append("=? WHERE ").append(id).append("=? AND (")
                .append(version).append(" IS NULL OR ").append(version).append("<=?)");
        try (var statement = connection.prepareStatement(sql.toString())) {
            statement.setQueryTimeout(statementTimeoutSeconds);
            for (QueuedWriteOperation member : operations) {
                if (!sameWriteShape(operation, member)) throw new IllegalArgumentException("Mixed PATCH shapes");
                int index = 1;
                for (var change : changes)
                    bindPatchValue(statement, index++, member.columns().get(change.getKey()),
                            JdbcWriteBehindSupport.columnType(entityRegistry, member, change.getKey()));
                statement.setLong(index++, member.version());
                bindPatchValue(statement, index++, member.id(), JdbcWriteBehindSupport.columnType(entityRegistry, member, member.idColumn()));
                statement.setLong(index, member.version());
                statement.addBatch();
            }
            VersionGuardedWriteSupport.verifyBatchOutcome(connection, entityRegistry, operations,
                    statement.executeBatch(), statementTimeoutSeconds);
        }
    }

    protected void bindPatchValue(java.sql.PreparedStatement statement, int index, String value, String type) throws SQLException {
        Object converted = JdbcWriteBehindSupport.convertValue(value, type == null ? "java.lang.String" : type);
        if (converted instanceof java.time.Instant instant) converted = java.sql.Timestamp.from(instant);
        else if (converted instanceof java.time.LocalDateTime local) converted = java.sql.Timestamp.valueOf(local);
        else if (converted instanceof java.time.LocalDate local) converted = java.sql.Date.valueOf(local);
        statement.setObject(index, converted);
    }

    private static String checkedIdentifier(String value, boolean qualified) {
        if (value == null || !value.matches(qualified ? "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,2}" : "[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Invalid SQL identifier for PATCH");
        return value;
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
