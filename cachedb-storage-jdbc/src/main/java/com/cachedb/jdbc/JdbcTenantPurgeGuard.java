package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/**
 * Durable tenant admission + lease + journal + bounded source-delete engine.
 * No DDL, Redis access, retry thread, private business transaction, or fence deletion.
 * Caller MUST invoke authorizeWrite(s) on the SAME connection/transaction used by the flusher.
 */
public final class JdbcTenantPurgeGuard implements Store {
    private final DataSource source;
    private final JdbcTenantPurgeDialect dialect;
    private final int timeout;
    private final Semaphore controls;
    private final Set<String> reviewedSources;
    private final Set<String> validatedSources=java.util.concurrent.ConcurrentHashMap.newKeySet();

    public JdbcTenantPurgeGuard(DataSource source, JdbcTenantPurgeDialect dialect, int timeoutSeconds, int maxControls) {
        this(source,dialect,timeoutSeconds,maxControls,Set.of());
    }
    /** Reviewed fingerprints attest bounded same-tenant trigger/RLS effects; empty disables source deletion. */
    public JdbcTenantPurgeGuard(DataSource source,JdbcTenantPurgeDialect dialect,int timeoutSeconds,int maxControls,
            Set<String> reviewedSourceBindings) {
        if(reviewedSourceBindings==null || reviewedSourceBindings.size()>64)throw new ProtectionException(INVALID_REQUEST);
        reviewedSourceBindings.forEach(ProtectionKey::token);
        reviewedSources=Set.copyOf(reviewedSourceBindings);
        this.source = Objects.requireNonNull(source);
        this.dialect = Objects.requireNonNull(dialect);
        if (timeoutSeconds < 1 || timeoutSeconds > 5 || maxControls < 1 || maxControls > 64)
            throw new ProtectionException(INVALID_REQUEST);
        timeout = timeoutSeconds;
        controls = new Semaphore(maxControls, true);
    }

    /** Migration/bootstrap only; never auto-create a missing guard on a producer/replay path. */
    public void provisionClosed(Generation generation) {
        admin();
        tx(connection -> {
            try (var statement = prepare(connection, dialect.provisionSql())) {
                key(statement, 1, generation.key());
                statement.setLong(3, generation.value());
                if (dialect == JdbcTenantPurgeDialect.MSSQL) key(statement, 4, generation.key());
                statement.executeUpdate();
            }
            Row row = lock(connection, generation.key());
            if (row.generation != generation.value() || !row.state.equals("CLOSED") || row.operation != null)
                throw new ProtectionException(OPERATION_CONFLICT);
            return null;
        });
    }

    /** Explicit bootstrap hook AFTER protocol migration, Redis CLOSED install and legacy drain. */
    public void openInitialGeneration(Generation generation) {
        admin();
        tx(connection -> {
            Row row = lock(connection, generation.key());
            if (row.generation != generation.value() || row.operation != null || progress(connection, generation.key()) != null)
                throw new ProtectionException(OPERATION_CONFLICT);
            updateGate(connection, generation.key(), generation.value(), "OPEN");
            return null;
        });
    }

    /**
     * Leaves the row lock held through the caller's business DML/commit. It NEVER commits,
     * rolls back, opens a connection, or touches cachedb_write_fence. Missing/legacy gen=0 fails closed.
     */
    public void authorizeWrite(Connection connection, Generation generation) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Generation guard requires the business transaction", "CDBGP");
        Row row = lock(connection, generation.key());
        if (row.generation != generation.value())
            throw new SQLException("Tenant generation rejected", "CDBGG");
        if (!row.state.equals("OPEN"))
            throw new SQLException("Tenant admission closed", "CDBGC");
    }

    /** At most one lock per tenant in deterministic order; the caller preserves atomic envelopes. */
    public void authorizeWrites(Connection connection, Collection<Generation> generations) throws SQLException {
        if (generations.size() > 128) throw new SQLException("Tenant guard batch exceeds 128", "CDBGP");
        var ordered = new TreeMap<ProtectionKey, Long>();
        for (Generation generation : generations) {
            Long previous = ordered.putIfAbsent(generation.key(), generation.value());
            if (previous != null && previous != generation.value())
                throw new SQLException("Mixed tenant generations in transaction", "CDBGG");
        }
        for (var entry : ordered.entrySet()) authorizeWrite(connection, new Generation(entry.getKey(), entry.getValue()));
    }

    /** Validates the request-local exact confirmation; no foreground write code calls this admin API. */
    public Lease acquireForTenant(Binding binding,String tenant,PurgeOptions options,boolean reenable) {
        admin();
        if (!binding.tenantPurgeEnabled() || !tenant.equals(options.confirmation()))
            throw new ProtectionException(CONFIRMATION_REQUIRED);
        return acquire(binding.key(tenant),options,reenable,binding.fingerprint());
    }
    @FunctionalInterface public interface PurgeSqlWork<T> { T run(Connection connection) throws SQLException; }
    /** Pure dialect descriptor; exposes no connection, execution or finalization authority. */
    public JdbcTenantPurgeDialect ownerBootstrapDialect() { return dialect; }

    Lease acquireOwnerBootstrap(Generation generation, Binding binding, UUID operation, int seconds,
            PurgeSqlWork<Void> initializeJournal) {
        admin();
        if (!validatedSources.contains(binding.fingerprint()) || !binding.tenantPurgeEnabled())
            throw new ProtectionException(UNWIRED_PROTOCOL);
        return tx(c -> {
            Row row = lock(c, generation.key());
            if (!row.state.equals("CLOSED") || row.generation != generation.value()
                    || progress(c, generation.key()) != null
                    || row.operation != null && !row.operation.equals(operation.toString()))
                throw new ProtectionException(MIGRATION_REQUIRED);
            if (live(c, generation.key())) throw new ProtectionException(BUSY);
            initializeJournal.run(c);
            long epoch = Math.addExact(row.epoch, 1);
            UUID owner = UUID.randomUUID();
            try (var q = prepare(c, "UPDATE cachedb_tenant_guard SET operation_id="+InitialSqlTokens.varchar(dialect,36)+",lease_epoch=?,"
                    + "lease_owner="+InitialSqlTokens.varchar(dialect,36)+",lease_until=" + dialect.expires(seconds)
                    + " WHERE "+InitialSqlTokens.pair(dialect,true)+"")) {
                q.setString(1, operation.toString()); q.setLong(2, epoch);
                q.setString(3, owner.toString()); key(q, 4, generation.key()); exactlyOne(q.executeUpdate());
            }
            return new Lease(generation.key(), operation, owner, epoch, seconds);
        });
    }

    <T> T inOwnerBootstrapTransaction(Lease lease, Generation generation, PurgeSqlWork<T> work) {
        admin();
        return tx(c -> {
            Row row = leaseRow(c, lease);
            if (!lease.key().equals(generation.key()) || !row.state.equals("CLOSED")
                    || row.generation != generation.value() || progress(c, lease.key()) != null)
                throw new ProtectionException(MIGRATION_REQUIRED);
            return work.run(c);
        });
    }



    void consumeInitialNamespaceBootstrap(Binding binding, Generation generation, JdbcNamespaceBootstrapGate.Request request) {
        admin();
        if (!validatedSources.contains(binding.fingerprint()) || !binding.tenantPurgeEnabled())
            throw new ProtectionException(UNWIRED_PROTOCOL);
        tx(c -> {
            Row row = lock(c, generation.key());
            if (row.generation != generation.value() || !row.state.equals("CLOSED") || row.epoch != 0
                    || row.operation != null || progress(c, generation.key()) != null)
                throw new ProtectionException(MIGRATION_REQUIRED);
            try (var q = prepare(c, "SELECT operation_id FROM cachedb_initial_bootstrap WHERE "+InitialSqlTokens.pair(dialect,false)+"")) {
                key(q, 1, generation.key()); try (var r = q.executeQuery()) {
                    if (r.next()) throw new ProtectionException(MIGRATION_REQUIRED);
                }
            }
            try (var q = prepare(c, "SELECT roster_token FROM cachedb_namespace_bootstrap WHERE roster_token="+InitialSqlTokens.varchar(dialect,64)+"")) {
                q.setString(1, request.rosterKeyToken()); try (var r = q.executeQuery()) {
                    if (r.next()) throw new ProtectionException(MIGRATION_REQUIRED);
                }
            }
            try (var q = prepare(c, "INSERT INTO cachedb_namespace_bootstrap(roster_token,binding_token,mapping_token,scope_token,"
                    + "tenant_token,generation,redis_run_id,incarnation_id) VALUES ("+InitialSqlTokens.varchar(dialect,64)+","+InitialSqlTokens.varchar(dialect,64)+","+InitialSqlTokens.varchar(dialect,64)+","+InitialSqlTokens.varchar(dialect,64)+","+InitialSqlTokens.varchar(dialect,64)+",?,"+InitialSqlTokens.varchar(dialect,40)+","+InitialSqlTokens.varchar(dialect,36)+")")) {
                q.setString(1, request.rosterKeyToken()); q.setString(2, request.bindingToken()); q.setString(3, request.mappingToken());
                key(q, 4, generation.key()); q.setLong(6, generation.value());
                q.setString(7, request.redisRunId()); q.setString(8, request.incarnationId().toString());
                exactlyOne(q.executeUpdate());
            } catch (SQLException duplicate) {
                String state = duplicate.getSQLState();
                if (state != null && state.startsWith("23")) throw new ProtectionException(MIGRATION_REQUIRED);
                throw duplicate;
            }
            return null;
        });
    }

    /** INITIAL is a separate durable purpose, never a fabricated purge Progress. */
    Lease acquireInitialBootstrap(Generation generation, Binding binding, UUID operation, int seconds,
            PurgeSqlWork<com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase> initialize) {
        admin();
        if (!validatedSources.contains(binding.fingerprint()) || !binding.tenantPurgeEnabled())
            throw new ProtectionException(UNWIRED_PROTOCOL);
        return tx(c -> {
            Row row = lock(c, generation.key());
            if (row.generation != generation.value() || progress(c, generation.key()) != null
                    || row.operation != null && !row.operation.equals(operation.toString()))
                throw new ProtectionException(MIGRATION_REQUIRED);
            if (live(c, generation.key())) throw new ProtectionException(BUSY);
            var phase = initialize.run(c);
            boolean activating = phase == com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase.SQL_OPEN
                    || phase == com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase.ACTIVE;
            if (activating ? !row.state.equals("OPEN") || !Objects.equals(row.operation, operation.toString())
                    : !row.state.equals("CLOSED")) throw new ProtectionException(MIGRATION_REQUIRED);
            long epoch = Math.addExact(row.epoch, 1); UUID owner = UUID.randomUUID();
            try (var q = prepare(c, "UPDATE cachedb_tenant_guard SET operation_id="+InitialSqlTokens.varchar(dialect,36)+",lease_epoch=?,lease_owner="+InitialSqlTokens.varchar(dialect,36)+",lease_until="
                    + dialect.expires(seconds) + " WHERE "+InitialSqlTokens.pair(dialect,true)+"")) {
                q.setString(1, operation.toString()); q.setLong(2, epoch); q.setString(3, owner.toString());
                key(q, 4, generation.key()); exactlyOne(q.executeUpdate());
            }
            return new Lease(generation.key(), operation, owner, epoch, seconds);
        });
    }

    public <T> T inInitialBootstrapTransaction(
            com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session, PurgeSqlWork<T> work) {
        admin();
        return tx(c -> {
            Lease lease = session.lease(); Row row = leaseRow(c, lease);
            if (row.generation != session.request().generation().value() || progress(c, lease.key()) != null)
                throw new ProtectionException(MIGRATION_REQUIRED);
            String phase;
            try (var q = prepare(c, "SELECT operation_id,generation,plan_token,manifest_token,phase_name"
                    + " FROM cachedb_initial_bootstrap WHERE "+InitialSqlTokens.pair(dialect,false)+"")) {
                key(q, 1, lease.key());
                try (var r = q.executeQuery()) {
                    if (!r.next() || !lease.operationId().toString().equals(r.getString(1))
                            || row.generation != r.getLong(2) || !session.planToken().equals(r.getString(3))
                            || !session.manifestToken().equals(r.getString(4))) throw new ProtectionException(OPERATION_CONFLICT);
                    phase = r.getString(5);
                }
            }
            boolean activating = "SQL_OPEN".equals(phase) || "ACTIVE".equals(phase);
            if (activating ? !row.state.equals("OPEN") : !row.state.equals("CLOSED"))
                throw new ProtectionException(WRITE_FENCED);
            T result = work.run(c);
            // Same row lock is held; expiry or lease replacement cannot authorize a late commit.
            leaseRow(c, lease);
            return result;
        });
    }

    void openInitialActivation(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session session,
            com.reactor.cachedb.core.protection.InitialTenantBootstrap.Activation activation, PurgeSqlWork<Void> recordOpen) {
        inInitialBootstrapTransaction(session, c -> {
            var range = activation.range();
            if (!session.lease().key().equals(range.key()) || range.generation() != session.request().generation().value()
                    || range.controlEpoch() != session.lease().epoch() || !session.planToken().equals(activation.planToken())
                    || !session.manifestToken().equals(activation.manifestToken())) throw new ProtectionException(OPERATION_CONFLICT);
            try (var q = prepare(c, "SELECT phase_name,activation_token FROM cachedb_initial_bootstrap"
                    + " WHERE "+InitialSqlTokens.pair(dialect,false)+"")) {
                key(q, 1, session.lease().key()); try (var r = q.executeQuery()) {
                    if (!r.next() || !"READY".equals(r.getString(1)) || !activation.token().equals(r.getString(2)))
                        throw new ProtectionException(MIGRATION_REQUIRED);
                }
            }
            try (var q = prepare(c, "SELECT verified_generation,last_generation,last_control_epoch,inventory_token,"
                    + "highwater,last_last,reservation_epoch,last_request,last_run_id FROM cachedb_tenant_allocator"
                    + " WHERE "+InitialSqlTokens.pair(dialect,true)+"")) {
                key(q, 1, session.lease().key()); try (var r = q.executeQuery()) {
                    if (!r.next() || r.getLong(1) != range.generation() || r.getLong(2) != range.generation()
                            || r.getLong(3) != range.controlEpoch() || !range.inventory().equals(r.getString(4))
                            || r.getLong(5) != range.last() || r.getLong(6) != range.last()
                            || r.getLong(7) != range.reservationEpoch() || !range.request().toString().equals(r.getString(8))
                            || !range.runId().equals(r.getString(9))) throw new ProtectionException(MIGRATION_REQUIRED);
                }
            }
            recordOpen.run(c);
            updateGate(c, session.lease().key(), range.generation(), "OPEN");
            return null;
        });
    }

    public boolean belongsTo(javax.sql.DataSource expected) { return source == expected; }
    /** Purge progress JDBC only; never execute Redis/network I/O inside this callback. */
    public <T> T inPurgeTransaction(Lease lease,PurgeSqlWork<T> work) {
        admin();
        return tx(connection -> {
            Row row=leaseRow(connection,lease);
            Progress p=requiredProgress(connection,lease);
            if (!row.state.equals("CLOSED") || row.generation!=p.closedGeneration())
                throw new ProtectionException(WRITE_FENCED);
            return work.run(connection);
        });
    }
    @FunctionalInterface public interface VersionSqlWork<T>{T run(Connection connection,long controlEpoch)throws SQLException;}
    /** Admin/background only. No caller may invoke this from foreground write acceptance. */
    public <T> T inClosedVersionTransaction(Lease lease,long generation,VersionSqlWork<T> work) {
        admin();
        return tx(c->{
            Row row=leaseRow(c,lease);Progress p=requiredProgress(c,lease);
            if(!row.state.equals("CLOSED") || row.generation!=generation
                    || generation!=p.closedGeneration() && generation!=p.newGeneration())
                throw new ProtectionException(WRITE_FENCED);
            // EMPTY owns its frozen reservation until the native receipt has committed as ACTIVE.
            if (p.stage() == Stage.REOPENING && JdbcEmptyTenantActivation.managed(c, lease, timeout, dialect))
                throw new ProtectionException(RECOVERY_REQUIRED);
            return work.run(c,row.epoch);
        });
    }
    /** Identity reservations require the binding attested by this actual purge operation. */
    public <T> T inClosedVersionTransaction(Lease lease,long generation,String bindingFingerprint,VersionSqlWork<T> work) {
        ProtectionKey.token(bindingFingerprint); Objects.requireNonNull(work);
        return inClosedVersionTransaction(lease,generation,(c,control)->{
            if (!bindingFingerprint.equals(requiredProgress(c,lease).bindingFingerprint()))
                throw new ProtectionException(OPERATION_CONFLICT);
            return work.run(c,control);
        });
    }
    /** INITIAL identity provisioning borrows its real session, never a fabricated purge journal. */
    public <T> T inInitialIdentityTransaction(InitialTenantBootstrap.Session session,String bindingFingerprint,VersionSqlWork<T> work) {
        ProtectionKey.token(bindingFingerprint); Objects.requireNonNull(work);
        return inInitialBootstrapTransaction(session,c->{
            Row row=leaseRow(c,session.lease());
            if (!row.state.equals("CLOSED")) throw new ProtectionException(WRITE_FENCED);
            try (var q=prepare(c,"SELECT phase_name FROM cachedb_initial_bootstrap WHERE "+InitialSqlTokens.pair(dialect,false))) {
                key(q,1,session.lease().key());
                try (var r=q.executeQuery()) {
                    if (!r.next() || !Set.of("CLOSURE","NATIVE_INVENTORY","DURABLE_VERSIONS","SOURCE_VERSIONS",
                            "HOT_WINDOW","VERIFY_WINDOW","READY").contains(r.getString(1)))
                        throw new ProtectionException(WRITE_FENCED);
                }
            }
            requireNamespaceBinding(c,session.lease().key(),bindingFingerprint);
            return work.run(c,row.epoch);
        });
    }
    /** Bounded background range refill, serialized with administrative closure by the same guard row. */
    public <T> T inOpenVersionTransaction(Generation generation,VersionSqlWork<T> work) {
        admin();
        return tx(c->{
            Row row=lock(c,generation.key());
            if(!row.state.equals("OPEN") || row.generation!=generation.value() || row.epoch<1)
                throw new ProtectionException(WRITE_FENCED);
            if (row.operation != null) {
                Progress p = progress(c, generation.key());
                if (p != null && p.stage() == Stage.REOPENING
                        && JdbcEmptyTenantActivation.managed(c, generation.key(), p.operationId(), timeout, dialect))
                    throw new ProtectionException(RECOVERY_REQUIRED);
            }
            return work.run(c,row.epoch);
        });
    }
    /** OPEN authority comes from durable purge progress, or the immutable first namespace grant. */
    public <T> T inOpenVersionTransaction(Generation generation,String bindingFingerprint,VersionSqlWork<T> work) {
        ProtectionKey.token(bindingFingerprint); Objects.requireNonNull(work);
        return inOpenVersionTransaction(generation,(c,control)->{
            Progress p=progress(c,generation.key());
            if (p!=null) {
                if (!bindingFingerprint.equals(p.bindingFingerprint())) throw new ProtectionException(OPERATION_CONFLICT);
            } else requireNamespaceBinding(c,generation.key(),bindingFingerprint);
            return work.run(c,control);
        });
    }
    /** Read retained activation authority on the caller's SAME locked OPEN transaction. Not a write grant. */
    public JdbcOpenActivationGrant readOpenActivationGrant(Connection c,Generation generation,String bindingFingerprint,
            String windowFingerprint,String manifestToken)throws SQLException {
        admin();
        if(c.getAutoCommit()) throw new ProtectionException(RECOVERY_REQUIRED);
        Row row=lock(c,generation.key());
        if(!row.state.equals("OPEN") || row.generation!=generation.value()) throw new ProtectionException(WRITE_FENCED);
        Progress p=progress(c,generation.key());
        if(p==null) requireNamespaceBinding(c,generation.key(),bindingFingerprint);
        else if(!bindingFingerprint.equals(p.bindingFingerprint()) || p.stage()!=Stage.REENABLED
                || !p.includeSource() || p.newGeneration()!=generation.value()
                || !p.operationId().toString().equals(row.operation)) throw new ProtectionException(RECOVERY_REQUIRED);
        return JdbcOpenActivationGrant.read(c,dialect,timeout,generation,bindingFingerprint,windowFingerprint,manifestToken,p,row.epoch);
    }
    private void requireNamespaceBinding(Connection c,ProtectionKey key,String bindingFingerprint)throws SQLException {
        // V019_7 indexes scope_token; two returned rows are already an ambiguous namespace grant.
        try (var q=prepare(c,"SELECT binding_token FROM cachedb_namespace_bootstrap WHERE scope_token="
                +InitialSqlTokens.varchar(dialect,64))) {
            q.setString(1,key.scopeToken()); q.setMaxRows(2);
            try (var r=q.executeQuery()) {
                if (!r.next()) throw new ProtectionException(MIGRATION_REQUIRED);
                if (!bindingFingerprint.equals(r.getString(1))) throw new ProtectionException(OPERATION_CONFLICT);
                if (r.next()) throw new ProtectionException(MIGRATION_REQUIRED);
            }
        }
    }
    public void markRequiresMigration(Lease lease) {
        admin();
        tx(connection -> {
            Row row=leaseRow(connection,lease); Progress p=requiredProgress(connection,lease);
            if (!row.state.equals("CLOSED") || row.generation!=p.closedGeneration())
                throw new ProtectionException(OPERATION_CONFLICT);
            Progress next=p.advance(Stage.REQUIRES_MIGRATION,p.inventoryToken(),p.cursor(),
                    p.pending(),p.pendingOffset(),p.redisRows(),p.redisKeys(),p.sourceRows(),0,null);
            writeProgress(connection,lease.key(),next,false); return null;
        });
    }

    @Override public void validateSource(Binding binding) {
        admin();
        if(!binding.tenantPurgeEnabled() || !reviewedSources.contains(binding.fingerprint()))
            throw new ProtectionException(UNWIRED_PROTOCOL);
        tx(connection -> {
            SourcePlan plan = binding.source();
            var meta = connection.getMetaData();
            String[] parts = plan.table().split("\\.", -1);
            String schema = parts.length == 2 ? parts[0] : connection.getSchema();
            String table = parts[parts.length - 1];
            if (meta.storesUpperCaseIdentifiers()) {
                table = table.toUpperCase(Locale.ROOT);
                if (schema != null) schema = schema.toUpperCase(Locale.ROOT);
            } else if (meta.storesLowerCaseIdentifiers()) {
                table = table.toLowerCase(Locale.ROOT);
                if (schema != null) schema = schema.toLowerCase(Locale.ROOT);
            }
            boolean exactType = false;
            try (var columns = meta.getColumns(connection.getCatalog(), schema, table, null)) {
                int rows = 0;
                while (columns.next()) {
                    if (++rows > 4096) throw new ProtectionException(INVALID_REQUEST);
                    if (!plan.tenantColumn().equalsIgnoreCase(columns.getString("COLUMN_NAME"))) continue;
                    int type = columns.getInt("DATA_TYPE");
                    exactType = dialect == JdbcTenantPurgeDialect.MSSQL ? type == Types.NVARCHAR
                            : type == Types.VARCHAR || dialect == JdbcTenantPurgeDialect.POSTGRES && type == Types.LONGVARCHAR;
                }
            }
            if (!exactType) throw new ProtectionException(INVALID_REQUEST);
            if (dialect != JdbcTenantPurgeDialect.MSSQL) {
                String sql = dialect == JdbcTenantPurgeDialect.POSTGRES ? "SHOW server_encoding"
                        : "SELECT value FROM nls_database_parameters WHERE parameter='NLS_CHARACTERSET'";
                try (var probe = prepare(connection, sql); var encoding = probe.executeQuery()) {
                    String expected = dialect == JdbcTenantPurgeDialect.POSTGRES ? "UTF8" : "AL32UTF8";
                    if (!encoding.next() || !expected.equalsIgnoreCase(encoding.getString(1)))
                        throw new ProtectionException(INVALID_REQUEST);
                }
            }
            boolean indexed = false;
            try (var indexes = meta.getIndexInfo(connection.getCatalog(), schema, table, false, false)) {
                int rows = 0;
                while (indexes.next()) {
                    if (++rows > 4096) throw new ProtectionException(INDEX_REQUIRED);
                    if (indexes.getInt("ORDINAL_POSITION") == 1
                            && plan.tenantColumn().equalsIgnoreCase(indexes.getString("COLUMN_NAME"))) indexed = true;
                }
            }
            if (!indexed) throw new ProtectionException(INDEX_REQUIRED);
            try (var foreign = meta.getExportedKeys(connection.getCatalog(), schema, table)) {
                int rows = 0;
                while (foreign.next()) {
                    if (++rows > 4096) throw new ProtectionException(INVALID_REQUEST);
                    int action = foreign.getInt("DELETE_RULE");
                    if (action != DatabaseMetaData.importedKeyNoAction && action != DatabaseMetaData.importedKeyRestrict)
                        throw new ProtectionException(INVALID_REQUEST);
                }
            }
            return null;
        });
        validatedSources.add(binding.fingerprint());
    }

    @Override public Lease acquire(ProtectionKey key, PurgeOptions options, boolean reenable, String bindingFingerprint) {
        admin();
        ProtectionKey.token(bindingFingerprint);
        return tx(connection -> {
            Row row = lock(connection, key);
            Progress progress = progress(connection, key);
            if (progress != null && progress.operationId().equals(options.operationId())
                    && !progress.bindingFingerprint().equals(bindingFingerprint)) throw new ProtectionException(OPERATION_CONFLICT);
            if (live(connection, key)) throw new ProtectionException(BUSY);
            if (reenable) {
                if (progress != null && progress.stage() == Stage.REENABLED
                        && JdbcEmptyTenantActivation.managed(connection, key, progress.operationId(), timeout, dialect))
                    throw new ProtectionException(OPERATION_CONFLICT);
                if (progress == null || !progress.operationId().equals(options.operationId())
                        || progress.includeSource() != options.includeSource()
                        || !Set.of(Stage.COMPLETE, Stage.REOPENING, Stage.REENABLED).contains(progress.stage()))
                    throw new ProtectionException(OPERATION_CONFLICT);
            } else if (progress != null && progress.operationId().equals(options.operationId())) {
                if (progress.includeSource() != options.includeSource()
                        || progress.stage() == Stage.REOPENING || progress.stage() == Stage.REENABLED
                        || !row.state.equals("CLOSED") || row.generation != progress.closedGeneration())
                    throw new ProtectionException(OPERATION_CONFLICT);
            } else {
                if (progress != null && (progress.stage() != Stage.REENABLED || !row.state.equals("OPEN")))
                    throw new ProtectionException(OPERATION_CONFLICT);
                boolean insert = progress == null;
                progress = new Progress(0, options.operationId(), row.generation, 0, Stage.FENCING,
                        options.includeSource(), options.operatorToken(), Instant.now(), null,
                        0, 0, 0, null, TenantPurgeProtocol.START, null, 0, bindingFingerprint);
                writeProgress(connection, key, progress, insert);
                updateGate(connection, key, row.generation, "CLOSED");
            }
            long epoch = Math.addExact(row.epoch, 1);
            UUID owner = UUID.randomUUID();
            try (var statement = prepare(connection, "UPDATE cachedb_tenant_guard SET operation_id=?,"
                    + "lease_epoch=?,lease_owner=?,lease_until=" + dialect.expires(options.leaseSeconds())
                    + " WHERE scope_token=? AND tenant_token=?")) {
                statement.setString(1, options.operationId().toString());
                statement.setLong(2, epoch);
                statement.setString(3, owner.toString());
                key(statement, 4, key);
                exactlyOne(statement.executeUpdate());
            }
            return new Lease(key, options.operationId(), owner, epoch, options.leaseSeconds());
        });
    }

    @Override public void renew(Lease lease) {
        admin();
        tx(connection -> {
            leaseRow(connection, lease);
            try (var statement = prepare(connection, "UPDATE cachedb_tenant_guard SET lease_until="
                    + dialect.expires(lease.seconds()) + " WHERE scope_token=? AND tenant_token=?")) {
                key(statement, 1, lease.key());
                exactlyOne(statement.executeUpdate());
            }
            return null;
        });
    }

    @Override public Progress load(Lease lease) {
        admin();
        return tx(connection -> {
            Row row = leaseRow(connection, lease);
            Progress current = requiredProgress(connection, lease);
            boolean activating = current.stage() == Stage.REOPENING || current.stage() == Stage.REENABLED;
            if (row.generation != (activating ? current.newGeneration() : current.closedGeneration())
                    || !activating && !row.state.equals("CLOSED")
                    || current.stage() == Stage.REENABLED && !row.state.equals("OPEN"))
                throw new ProtectionException(INVALID_PROGRESS);
            return current;
        });
    }

    @Override public Progress save(Lease lease, Progress before, Progress after) {
        admin();
        return tx(connection -> {
            Row row = leaseRow(connection, lease);
            Progress current = requiredProgress(connection, lease);
            validateAdvance(before, current, after);
            if (after.stage() == Stage.REENABLED) {
                if (JdbcEmptyTenantActivation.managed(connection, lease, timeout, dialect))
                    throw new ProtectionException(RECOVERY_REQUIRED);
                if (!row.state.equals("OPEN") || row.generation != after.newGeneration())
                    throw new ProtectionException(INVALID_PROGRESS);
            } else if (!row.state.equals("CLOSED") || row.generation != current.closedGeneration())
                throw new ProtectionException(INVALID_PROGRESS);
            writeProgress(connection, lease.key(), after, false);
            return after;
        });
    }

    @Override public Progress deleteSource(Lease lease, Binding binding, String rawTenant, int limit) {
        admin();
        if (!binding.key(rawTenant).equals(lease.key())) throw new ProtectionException(CONFIRMATION_REQUIRED);
        if (!binding.tenantPurgeEnabled() || !validatedSources.contains(binding.fingerprint()))
            throw new ProtectionException(UNWIRED_PROTOCOL);
        if (limit < 1 || limit > 128) throw new ProtectionException(INVALID_REQUEST);
        return tx(connection -> {
            Row row = leaseRow(connection, lease);
            Progress current = requiredProgress(connection, lease);
            if (!row.state.equals("CLOSED") || row.generation != current.closedGeneration()
                    || current.stage() != Stage.SOURCE || !current.includeSource()
                    || !current.bindingFingerprint().equals(binding.fingerprint())) throw new ProtectionException(INVALID_PROGRESS);
            int removed;
            try (var statement = prepare(connection, dialect.deleteSourceSql(binding.source(), limit))) {
                dialect.bindTenant(statement, rawTenant, dialect == JdbcTenantPurgeDialect.MSSQL ? 1 : 2);
                removed = statement.executeUpdate();
            }
            if (removed < 0 || removed > limit) throw new ProtectionException(INVALID_PROGRESS);
            Progress next = current.advance(removed == 0 ? Stage.VERIFYING : Stage.SOURCE,
                    current.inventoryToken(), current.cursor(), null, 0, current.redisRows(),
                    current.redisKeys(), Math.addExact(current.sourceRows(), removed), 0, null);
            // Counts and business DELETE are committed atomically on this SAME connection.
            writeProgress(connection, lease.key(), next, false);
            return next;
        });
    }

    /** Live exact-tenant source probe under the SAME CLOSED guard row. Administrative only. */
    public boolean verifySourceEmpty(Lease lease,Binding binding,String tenant) {
        if(!binding.key(tenant).equals(lease.key())||!validatedSources.contains(binding.fingerprint()))
            throw new ProtectionException(UNWIRED_PROTOCOL);
        return inPurgeTransaction(lease,c->{
            var progress=requiredProgress(c,lease);
            if(progress.stage()!=Stage.VERIFYING||!progress.includeSource())throw new ProtectionException(INVALID_PROGRESS);
            try(var q=prepare(c,dialect.sourceProbeSql(binding.source()))){
                dialect.bindTenant(q,tenant,1);
                try(var rows=q.executeQuery()){return !rows.next();}
            }
        });
    }

    @Override public Progress prepareReenable(Lease lease) {
        admin();
        return tx(connection -> {
            Row row = leaseRow(connection, lease);
            Progress current = requiredProgress(connection, lease);
            // Retained source/history cannot silently become new-generation data.
            if (!current.includeSource()) throw new ProtectionException(MIGRATION_REQUIRED);
            if (current.stage() == Stage.REOPENING || current.stage() == Stage.REENABLED) {
                if (row.generation != current.newGeneration()
                        || current.stage() == Stage.REENABLED && !row.state.equals("OPEN"))
                    throw new ProtectionException(INVALID_PROGRESS);
                return current;
            }
            if (current.stage() != Stage.COMPLETE || !row.state.equals("CLOSED")
                    || row.generation != current.closedGeneration()) throw new ProtectionException(OPERATION_CONFLICT);
            long generation = Math.addExact(row.generation, 1);
            Progress next = current.advance(Stage.REOPENING, current.inventoryToken(), current.cursor(),
                    null, 0, current.redisRows(), current.redisKeys(), current.sourceRows(), generation, current.completedAt());
            updateGate(connection, lease.key(), generation, "CLOSED");
            writeProgress(connection, lease.key(), next, false);
            return next;
        });
    }

    @Override public void openPreparedGeneration(Lease lease) {
        admin();
        tx(connection -> {
            Row row = leaseRow(connection, lease);
            Progress current = requiredProgress(connection, lease);
            if (JdbcEmptyTenantActivation.managed(connection, lease, timeout, dialect))
                throw new ProtectionException(RECOVERY_REQUIRED);
            if (!current.includeSource()) throw new ProtectionException(MIGRATION_REQUIRED);
            if (current.stage() != Stage.REOPENING || current.newGeneration() != row.generation)
                throw new ProtectionException(OPERATION_CONFLICT);
            try(var q=prepare(connection,"SELECT verified_generation,last_generation,last_control_epoch,inventory_token,highwater,last_last"
                    +" FROM cachedb_tenant_allocator WHERE scope_token=? AND tenant_token=?")){
                key(q,1,lease.key());try(var r=q.executeQuery()){
                    if(!r.next() || r.getLong(1)!=row.generation || r.getLong(2)!=row.generation
                            || r.getLong(3)!=lease.epoch() || r.getString(4)==null || r.getLong(5)!=r.getLong(6))
                        throw new ProtectionException(MIGRATION_REQUIRED);
                }
            }
            updateGate(connection, lease.key(), row.generation, "OPEN");
            return null;
        });
    }

    @Override public void release(Lease lease) {
        admin();
        tx(connection -> {
            // Never clear a newer owner's lease, even after this worker's timeout.
            try (var statement = prepare(connection, "UPDATE cachedb_tenant_guard SET lease_owner=NULL,lease_until=NULL"
                    + " WHERE scope_token=? AND tenant_token=? AND lease_epoch=? AND lease_owner=?")) {
                key(statement, 1, lease.key()); statement.setLong(3, lease.epoch());
                statement.setString(4, lease.owner().toString()); statement.executeUpdate();
            }
            return null;
        });
    }

    record ActivationState(Connection connection, Progress progress, long generation, String gate) {}
    @FunctionalInterface interface ActivationWork<T> { T run(ActivationState state) throws SQLException; }

    /** Small SQL-only transaction. No Redis/network callback may run while this row is held. */
    <T> T activationTransaction(Lease lease, ActivationWork<T> work) {
        admin();
        return tx(c -> {
            Row row = leaseRow(c, lease);
            T result = work.run(new ActivationState(c, requiredProgress(c, lease), row.generation, row.state));
            leaseRow(c, lease);
            return result;
        });
    }

    <T> T activationInspection(ProtectionKey key, ActivationWork<T> work) {
        admin();
        return tx(c -> {
            Row row = lock(c, key);
            return work.run(new ActivationState(c, progress(c, key), row.generation, row.state));
        });
    }

    void prepareEmptyGeneration(ActivationState state, Lease lease) throws SQLException {
        Progress p = state.progress;
        if (p.stage() != Stage.COMPLETE || !p.includeSource() || !state.gate.equals("CLOSED")
                || state.generation != p.closedGeneration()) throw new ProtectionException(OPERATION_CONFLICT);
        long next = Math.addExact(p.closedGeneration(), 1);
        updateGate(state.connection, lease.key(), next, "CLOSED");
        writeProgress(state.connection, lease.key(), p.advance(Stage.REOPENING, p.inventoryToken(), p.cursor(),
                null, 0, p.redisRows(), p.redisKeys(), p.sourceRows(), next, p.completedAt()), false);
    }

    void openEmptyGeneration(ActivationState state, Lease lease) throws SQLException {
        if (state.progress.stage() != Stage.REOPENING || !state.gate.equals("CLOSED")
                || state.generation != state.progress.newGeneration()) throw new ProtectionException(OPERATION_CONFLICT);
        updateGate(state.connection, lease.key(), state.generation, "OPEN");
    }

    void completeEmptyGeneration(ActivationState state, Lease lease) throws SQLException {
        Progress p = state.progress;
        if (p.stage() != Stage.REOPENING || !state.gate.equals("OPEN")
                || state.generation != p.newGeneration()) throw new ProtectionException(OPERATION_CONFLICT);
        writeProgress(state.connection, lease.key(), p.advance(Stage.REENABLED, p.inventoryToken(), p.cursor(),
                null, 0, p.redisRows(), p.redisKeys(), p.sourceRows(), p.newGeneration(), p.completedAt()), false);
    }

    private static void validateAdvance(Progress before, Progress current, Progress after) {
        if (!before.equals(current) || after.revision() != Math.addExact(current.revision(), 1)
                || !after.operationId().equals(current.operationId()) || after.closedGeneration() != current.closedGeneration()
                || after.includeSource() != current.includeSource() || !after.operatorToken().equals(current.operatorToken())
                || !after.startedAt().equals(current.startedAt()) || !after.bindingFingerprint().equals(current.bindingFingerprint())
                || after.redisRows() < current.redisRows()
                || after.redisKeys() < current.redisKeys() || after.sourceRows() < current.sourceRows())
            throw new ProtectionException(INVALID_PROGRESS);
        if (after.stage() == Stage.SOURCE && !after.includeSource()
                || current.stage() == Stage.REDIS && after.stage() == Stage.VERIFYING && after.includeSource())
            throw new ProtectionException(INVALID_PROGRESS);
        boolean allowed = switch (current.stage()) {
            case FENCING -> after.stage() == Stage.REDIS || after.stage() == Stage.REQUIRES_MIGRATION;
            case REDIS -> Set.of(Stage.REDIS, Stage.SOURCE, Stage.VERIFYING, Stage.REQUIRES_MIGRATION).contains(after.stage());
            case VERIFYING -> after.stage() == Stage.COMPLETE || after.stage() == Stage.REQUIRES_MIGRATION;
            case REOPENING -> after.stage() == Stage.REENABLED;
            default -> false;
        };
        if (!allowed) throw new ProtectionException(INVALID_PROGRESS);
    }

    private Row leaseRow(Connection connection, Lease lease) throws SQLException {
        Row row = lock(connection, lease.key());
        if (!live(connection, lease.key()) || row.epoch != lease.epoch() || !Objects.equals(row.owner, lease.owner().toString())
                || !Objects.equals(row.operation, lease.operationId().toString()))
            throw new ProtectionException(LEASE_LOST);
        return row;
    }
    /** Read DB clock AFTER acquiring the row lock; a pre-lock expression can become stale while waiting. */
    private boolean live(Connection connection, ProtectionKey key) throws SQLException {
        try (var query = prepare(connection, "SELECT CASE WHEN lease_until>" + dialect.now()
                + " THEN 1 ELSE 0 END FROM cachedb_tenant_guard WHERE scope_token=? AND tenant_token=?")) {
            key(query, 1, key);
            try (var result = query.executeQuery()) {
                if (!result.next()) throw new ProtectionException(INVALID_PROGRESS);
                return result.getInt(1) == 1;
            }
        }
    }
    private Progress requiredProgress(Connection connection, Lease lease) throws SQLException {
        Progress progress = progress(connection, lease.key());
        if (progress == null || !progress.operationId().equals(lease.operationId())) throw new ProtectionException(INVALID_PROGRESS);
        return progress;
    }
    private record Row(long generation, String state, String operation, long epoch, String owner, boolean live) {}
    private Row lock(Connection connection, ProtectionKey key) throws SQLException {
        try (var statement = prepare(connection, dialect.lockSql())) {
            key(statement, 1, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing provisioned tenant generation guard", "CDBGM");
                Row row = new Row(result.getLong(1), result.getString(2), result.getString(3),
                        result.getLong(4), result.getString(5), result.getInt(6) == 1);
                if (row.generation <= 0 || row.epoch < 0 || !Set.of("OPEN", "CLOSED").contains(row.state))
                    throw new SQLException("Invalid tenant guard", "CDBGP");
                return row;
            }
        }
    }
    private Progress progress(Connection connection, ProtectionKey key) throws SQLException {
        try (var statement = prepare(connection, "SELECT payload FROM cachedb_purge_progress WHERE scope_token=? AND tenant_token=?")) {
            key(statement, 1, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return null;
                try (var stream = result.getBinaryStream(1)) {
                    if (stream == null) throw new ProtectionException(INVALID_PROGRESS);
                    return PurgeProgressCodec.decode(stream.readNBytes(PurgeProgressCodec.MAX_BYTES + 1));
                } catch (IOException invalid) { throw new ProtectionException(INVALID_PROGRESS); }
            }
        }
    }
    private void writeProgress(Connection connection, ProtectionKey key, Progress progress, boolean insert) throws SQLException {
        byte[] bytes = PurgeProgressCodec.encode(progress);
        String sql = insert ? "INSERT INTO cachedb_purge_progress(payload,scope_token,tenant_token) VALUES (?,?,?)"
                : "UPDATE cachedb_purge_progress SET payload=? WHERE scope_token=? AND tenant_token=?";
        try (var statement = prepare(connection, sql)) {
            statement.setBinaryStream(1, new ByteArrayInputStream(bytes), bytes.length);
            key(statement, 2, key);
            exactlyOne(statement.executeUpdate());
        }
    }
    private void updateGate(Connection connection, ProtectionKey key, long generation, String state) throws SQLException {
        try (var statement = prepare(connection, "UPDATE cachedb_tenant_guard SET generation=?,gate_state=?"
                + " WHERE scope_token=? AND tenant_token=?")) {
            statement.setLong(1, generation); statement.setString(2, state); key(statement, 3, key);
            exactlyOne(statement.executeUpdate());
        }
    }
    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(timeout); return statement; }
        catch (SQLException failure) { statement.close(); throw failure; }
    }
    private static void key(PreparedStatement statement, int index, ProtectionKey key) throws SQLException {
        statement.setString(index, key.scopeToken()); statement.setString(index + 1, key.tenantToken());
    }
    private static void exactlyOne(int count) { if (count != 1) throw new ProtectionException(INVALID_PROGRESS); }
    private static void admin() {
        if (!CacheTenantContext.isAdministrative()) throw new ProtectionException(ADMIN_REQUIRED);
    }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }

    private <T> T tx(Work<T> work) {
        if (!controls.tryAcquire()) throw new ProtectionException(BUSY);
        try (Connection connection = source.getConnection()) {
            if (!connection.getAutoCommit()) throw new ProtectionException(STORAGE_UNAVAILABLE);
            connection.setAutoCommit(false);
            boolean clean = false;
            try {
                T value = work.run(connection);
                connection.commit();
                clean = true;
                return value;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); clean = true; }
                catch (SQLException rollbackFailure) {
                    try { connection.abort(Runnable::run); }
                    catch (SQLException abortFailure) { /* Do not reuse or expose vendor bind text. */ }
                }
                throw failure;
            } finally {
                if (clean) connection.setAutoCommit(true);
            }
        } catch (SQLException sanitized) {
            throw new ProtectionException(STORAGE_UNAVAILABLE);
        } finally { controls.release(); }
    }
}
