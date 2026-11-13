package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.queue.FailureClassifyingFlusher;
import com.reactor.cachedb.core.observe.*;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.EntityRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;

/** Shared transaction boundary; dialects retain their batching, ordering and retry policies. */
public abstract class AbstractJdbcWriteBehindFlusher implements FailureClassifyingFlusher {
    private final java.util.concurrent.locks.ReentrantLock reorderLogLock = new java.util.concurrent.locks.ReentrantLock();
    protected final DataSource dataSource;
    protected final EntityRegistry entityRegistry;
    protected final JdbcWriteFence writeFence;
    private final JdbcTenantPurgeGuard tenantGuard;
    private final com.reactor.cachedb.core.config.WriteBehindConfig flushConfig;
    private final JdbcSchemaDialect columnDialect;
    private final EncryptedJdbcSchemaGuard encryptedSchema = new EncryptedJdbcSchemaGuard();
    private final int statementTimeoutSeconds;
    private final String observationDatabaseSystem;
    private CacheObservationRecorder observationRecorder = NoOpCacheObservationRecorder.INSTANCE;
    private boolean observationEnabled;
    private boolean observationOriginLinksEnabled;
    public final AbstractJdbcWriteBehindFlusher withObservationOriginLinksEnabled(boolean enabled) {
        observationOriginLinksEnabled = enabled; return this;
    }

    /** Configure the native flusher before publication, including inside custom factories. */
    public final AbstractJdbcWriteBehindFlusher withObservationRecorder(CacheObservationRecorder recorder) {
        Objects.requireNonNull(recorder, "recorder");
        if (observationRecorder != NoOpCacheObservationRecorder.INSTANCE && observationRecorder != recorder)
            throw new IllegalStateException("Observation recorder is already assigned");
        observationRecorder = recorder; observationEnabled = recorder.enabled(); return this;
    }
    private CacheObservationRecorder.Scope batchObservation(List<QueuedWriteOperation> operations) {
        var scope = CacheObservations.start(observationRecorder, CacheOperation.FLUSH_BATCH, "batch");
        try {
            scope.databaseSystem(observationDatabaseSystem);
            scope.tier(Tier.SOURCE);
            if (!observationOriginLinksEnabled) return scope;
            var origins = new ArrayList<String>(Math.min(operations.size(), CacheTraceparent.MAX_ORIGINS_EXAMINED));
            for (int i = 0; i < Math.min(operations.size(), CacheTraceparent.MAX_ORIGINS_EXAMINED); i++)
                origins.add(operations.get(i).traceMetadata().get("traceparent"));
            scope.originLinks(CacheTraceparent.originLinks(origins));
            return scope;
        } catch (RuntimeException instrumentationFailure) {
            CacheObservations.close(scope); return NoOpCacheObservationRecorder.SCOPE;
        }
    }
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
        this.tenantGuard = new JdbcTenantPurgeGuard(dataSource,JdbcTenantPurgeDialect.valueOf(provider.name()),
                Math.max(1,Math.min(5,timeoutSeconds)),1);
        this.flushConfig = config;
        this.statementTimeoutSeconds = Math.max(1, timeoutSeconds);
        this.observationDatabaseSystem = switch (provider) {
            case POSTGRES -> "postgresql";
            case MSSQL -> "mssql";
            case ORACLE -> "oracle";
        };
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
        var databaseMetadata = connection.getMetaData();
        int maxColumns = dialect == null
                ? columnDialect.resolveMaxColumnsInTable(databaseMetadata)
                : dialect.resolveMaxColumnsInTable(databaseMetadata);
        for (QueuedWriteOperation operation : operations) {
            if (entityRegistry != null) {
                var binding = entityRegistry.find(operation.entityName());
                if (binding.isPresent() && com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(binding.get().metadata()))
                    encryptedSchema.verify(databaseMetadata, binding.get().metadata(), encryptedSourceValues(operation));
            }
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

    protected static final class PreparedWrites {
        private final List<QueuedWriteOperation> operations;
        private final boolean protectedWrites;
        private PreparedWrites(List<QueuedWriteOperation> operations, boolean protectedWrites) {
            this.operations = operations; this.protectedWrites = protectedWrites;
        }
        public PreparedWrites slice(int from, int to) { return new PreparedWrites(operations.subList(from, to), protectedWrites); }
    }

    protected final PreparedWrites prepareWrites(List<QueuedWriteOperation> operations) {
        boolean encrypted = com.reactor.cachedb.core.crypto.EncryptedQueuedWrites.protectedBatch(entityRegistry, operations);
        try {
            return new PreparedWrites(com.reactor.cachedb.core.crypto.EncryptedQueuedWrites.sourceColumns(entityRegistry, operations), encrypted);
        } catch (com.reactor.cachedb.core.crypto.CacheEncryptionException safe) { throw safe; }
        catch (RuntimeException unsafe) {
            if (encrypted) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
            throw unsafe;
        }
    }

    /** Validate the entire prepared call before fence initialization, without borrowing on cache hits. */
    protected final void initializeWriteFence(PreparedWrites prepared) throws SQLException {
        boolean needsMetadata = false;
        if (entityRegistry != null && prepared.protectedWrites) {
            for (var operation : prepared.operations) {
                var binding = entityRegistry.find(operation.entityName());
                if (binding.isPresent() && !encryptedSchema.verifyCached(binding.get().metadata(), encryptedSourceValues(operation)))
                    needsMetadata = true;
            }
            if (needsMetadata) {
                // Close this metadata connection before fence initialization or borrowing the business connection.
                try (var connection = dataSource.getConnection()) {
                    var database = connection.getMetaData();
                    for (var operation : prepared.operations) {
                        var binding = entityRegistry.find(operation.entityName());
                        if (binding.isPresent())
                            encryptedSchema.verify(database, binding.get().metadata(), encryptedSourceValues(operation));
                    }
                }
            }
        }
        writeFence.initialize();
    }

    private static Map<String, String> encryptedSourceValues(QueuedWriteOperation operation) {
        return operation.type() == com.reactor.cachedb.core.model.OperationType.DELETE ? Map.of() : operation.columns();
    }

    protected final SQLException safeSqlFailure(List<QueuedWriteOperation> operations, SQLException failure) {
        return com.reactor.cachedb.core.crypto.EncryptedQueuedWrites.protectedBatch(entityRegistry, operations)
                ? com.reactor.cachedb.core.crypto.CacheFieldRedactor.redactSqlFailure(failure) : failure;
    }

    private void inSourceTransaction(Connection connection, PreparedWrites prepared, Integer isolation,
            SessionSettings settings, BatchWriter writer) throws SQLException {
        try {
            inTransactionUnobserved(connection, prepared.operations, isolation, settings, writer);
        } catch (SQLException unsafe) {
            if (prepared.protectedWrites) throw com.reactor.cachedb.core.crypto.CacheFieldRedactor.redactSqlFailure(unsafe);
            throw unsafe;
        } catch (com.reactor.cachedb.core.crypto.CacheEncryptionException safe) { throw safe;
        } catch (RuntimeException unsafe) {
            if (prepared.protectedWrites) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
            throw unsafe;
        }
    }

    protected final void inTransaction(
            Connection connection, List<QueuedWriteOperation> operations, Integer isolation,
            SessionSettings settings, BatchWriter writer) throws SQLException {
        inTransaction(connection, prepareWrites(operations), isolation, settings, writer);
    }

    protected final void inTransaction(Connection connection, PreparedWrites prepared, Integer isolation,
            SessionSettings settings, BatchWriter writer) throws SQLException {
        List<QueuedWriteOperation> operations = prepared.operations;
        if (!observationEnabled) {
            inSourceTransaction(connection, prepared, isolation, settings, writer);
            return;
        }
        // Origins must be available before makeCurrent starts the native observation.
        var scope = batchObservation(operations);
        try (var binding = CacheObservationContext.attach(scope)) {
            inSourceTransaction(connection, prepared, isolation, settings, writer);
            CacheObservations.success(scope, CacheOperation.FLUSH_BATCH, null);
        } catch (SQLException | RuntimeException | Error failure) {
            CacheObservations.error(scope, failure); throw failure;
        } finally { CacheObservations.close(scope); }
    }

    private void inTransactionUnobserved(
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
            authorizeTenantGenerations(connection,operations);
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

    private void authorizeTenantGenerations(Connection connection,List<QueuedWriteOperation> operations)throws SQLException {
        if(entityRegistry==null){
            for(var operation:operations)
                if(com.reactor.cachedb.core.protection.TenantWriteStampContext.current(operation)!=null)
                    throw new SQLException("Stamped writes require a registered source binding","CDBGP");
            return;
        }
        var protectedBindings=entityRegistry.tenantProtectedBindings();
        if(protectedBindings.isEmpty()&&!com.reactor.cachedb.core.protection.TenantWriteStampContext.hasStamps())return;
        var targets=new java.util.HashMap<String,com.reactor.cachedb.core.model.EntityMetadata<?,?>>();
        int registered=0;
        for(var binding:protectedBindings){
            if(++registered>10000)throw new SQLException("Protection registry exceeds bounded validation","CDBGP");
            if(binding.metadata().tenantPurgeEnabled()){
                String table=binding.metadata().tableName().toUpperCase(java.util.Locale.ROOT);
                var previous=targets.putIfAbsent(table,binding.metadata());
                if(previous!=null && !previous.entityName().equals(binding.metadata().entityName()))
                    throw new SQLException("Ambiguous protected source ownership","CDBGP");
            }
        }
        var generations=new java.util.TreeMap<com.reactor.cachedb.core.protection.ProtectionKey,Long>();
        for(var operation:operations){
            var binding=entityRegistry.find(operation.entityName());
            var original=com.reactor.cachedb.core.protection.TenantWriteStampContext.current(operation);
            if(binding.isEmpty() && original!=null)throw new SQLException("Original protected binding required","CDBGP");
            if(binding.isEmpty() || !binding.get().metadata().tenantPurgeEnabled() && original==null){
                if(operation.tableName()!=null && targets.containsKey(operation.tableName().toUpperCase(java.util.Locale.ROOT)))
                    throw new SQLException("Original protected binding required","CDBGP");
                continue;
            }
            var metadata=binding.get().metadata();
            if(!metadata.redisNamespace().equals(operation.redisNamespace()) || !metadata.tableName().equals(operation.tableName()))
                throw new SQLException("Protected write binding mismatch","CDBGP");
            var generation=original;
            if(generation==null || !generation.key().scopeToken().equals(
                    com.reactor.cachedb.core.protection.ProtectionKey.hash(operation.redisNamespace())))
                throw new SQLException("Original tenant generation required","CDBGM");
            if(metadata.tenantFieldName()==null)throw new SQLException("Stamped writes require tenant metadata","CDBGP");
            String tenant=operation.columns().get(metadata.tenantFieldName());
            if(tenant!=null && !com.reactor.cachedb.core.protection.ProtectionKey.of(operation.redisNamespace(),tenant).equals(generation.key()))
                throw new SQLException("Protected write tenant mismatch","CDBGG");
            Long prior=generations.putIfAbsent(generation.key(),generation.value());
            if(prior!=null && prior.longValue()!=generation.value())throw new SQLException("Mixed tenant generations","CDBGG");
            if(generations.size()>128)throw new SQLException("Tenant guard batch exceeds 128","CDBGP");
        }
        for(var entry:generations.entrySet())tenantGuard.authorizeWrite(connection,
                new com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation(entry.getKey(),entry.getValue()));
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
        reorderLogLock.lock();
        try {
            for (var operation : operations) {
                String name = operation.entityName();
                if (name.length() > 512) name = name.substring(0, 512);
                if (reorderLoggedEntities.size() < 1024 && reorderLoggedEntities.add(name))
                    System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                            "Independent write regrouping enabled for " + name
                            + "; same-key order preserved; cross-row independence is the application's contract");
            }
        } finally {
            reorderLogLock.unlock();
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
