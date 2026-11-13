package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.SourcePlan;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/** Real first-root INITIAL activation; the original rejecting source owns every later SQL attempt. */
public final class NativeProducerInitialFixture implements DataSource, AutoCloseable {
    public record Activated(RedisTenantProducer producer, long controlEpoch, long reservationEpoch,
            JdbcTenantPurgeGuard controlGuard, Binding binding) {}
    private final GeneratedInitialSqlSandbox sandbox;
    private final EntityMetadata<?, ?> metadata;
    private final DataSource forbidden;
    private final AtomicBoolean attempted = new AtomicBoolean();
    private final AtomicReference<Thread> bootstrapThread = new AtomicReference<>();
    private final AtomicInteger bootstrapConnections = new AtomicInteger();

    private NativeProducerInitialFixture(GeneratedInitialSqlSandbox sandbox,
            EntityMetadata<?, ?> metadata, DataSource forbidden) {
        this.sandbox = sandbox; this.metadata = metadata; this.forbidden = Objects.requireNonNull(forbidden);
    }

    public static NativeProducerInitialFixture create(EntityMetadata<?, ?> metadata,
            DataSource forbidden) throws Exception {
        Objects.requireNonNull(forbidden);
        if (!Boolean.getBoolean("cachedb.initial.isolatedSql"))
            throw new IllegalStateException("Explicit disposable INITIAL SQL fixture required");
        if (!metadata.tenantPurgeEnabled() || !"ProtectedRow".equals(metadata.entityName())
                || !"protected_rows".equals(metadata.tableName()) || !"protected_rows".equals(metadata.redisNamespace())
                || !"id".equals(metadata.idColumn()) || !"tenant_id".equals(metadata.tenantFieldName())
                || !"entity_version".equals(metadata.versionColumn())
                || !"java.lang.Long".equals(metadata.columnTypes().get("id"))
                || !Set.copyOf(metadata.columns()).equals(Set.of("id", "tenant_id", "status", "contact", "amount", "hidden")))
            throw new IllegalArgumentException("Reviewed generated encrypted Long-ID point mapping required");
        var fixture = new NativeProducerInitialFixture(new GeneratedInitialSqlSandbox("postgres"), metadata, forbidden);
        try {
            try (var connection = fixture.sandbox.source.getConnection(); var statement = connection.createStatement()) {
                fixture.sandbox.assertScope(connection); statement.setQueryTimeout(15);
                statement.executeUpdate("CREATE TABLE protected_rows (id BIGINT NOT NULL PRIMARY KEY, "
                        + "tenant_id VARCHAR(64) COLLATE \"C\" NOT NULL, status VARCHAR(32), "
                        + "contact VARCHAR(128), amount VARCHAR(128), hidden VARCHAR(128), entity_version BIGINT NOT NULL)");
                statement.executeUpdate("CREATE INDEX protected_rows_tenant_id ON protected_rows(tenant_id,id)");
                statement.executeUpdate("CREATE INDEX protected_rows_tenant_version ON protected_rows(tenant_id,entity_version)");
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM protected_rows")) {
                    if (!rows.next() || rows.getLong(1) != 0 || rows.next()) throw new AssertionError("Owned source must be empty");
                }
            }
            return fixture;
        } catch (Exception | Error failure) {
            Throwable primary = failure instanceof SQLException sql ? GeneratedInitialSqlSandbox.safe(sql) : failure;
            try { fixture.close(); } catch (Exception | Error cleanup) { primary.addSuppressed(cleanup); }
            if (primary instanceof Exception checked) throw checked;
            throw (Error) primary;
        }
    }

    @SuppressWarnings("unchecked")
    public <T, ID> Activated bootstrap(CacheDatabase database, EntityMetadata<T, ID> actual,
            String tenant, int capacity) {
        if (actual != metadata || database.dataSource() != this || !attempted.compareAndSet(false, true)
                || !bootstrapThread.compareAndSet(null, Thread.currentThread()))
            throw new AssertionError("Only the first actual root may create this namespace birth");
        // The checked generated metadata declares Long; no synthetic metadata/layout token is substituted.
        var longs = (EntityMetadata<T, Long>) (EntityMetadata<?, ?>) actual;
        try (var admin = CacheTenantContext.administrative()) {
            var binding = new Binding(longs.entityType(), longs.redisNamespace(),
                    new SourcePlan(longs.tableName(), longs.tenantFieldName(), longs.idColumn()),
                    RedisInitialSourceLayout.layoutToken(longs, database.redisKeyStrategy(), database.config().queryIndex()),
                    10000, 128, 1048576, true);
            var guard = new JdbcTenantPurgeGuard(this, JdbcTenantPurgeDialect.POSTGRES, 2, 1, Set.of(binding.fingerprint()));
            var generation = new Generation(binding.key(tenant), 1);
            guard.provisionClosed(generation);
            try (var namespace = database.prepareInitialNamespace(longs, binding, guard,
                    JdbcSourceOwnerOnboarding.longIds(), 1L, 1L, 1, 65536, 4096, 1048576, capacity);
                    var wire = new BoundedRedisWire(new InetSocketAddress(
                            System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                            Integer.getInteger("cachedb.snapshot.redis.port", 16383)),
                            null, null, null, null, 0, 2000, 2000, 1)) {
                var producer = namespace.bootstrap(new JdbcNamespaceBootstrapGate(guard,
                        Set.of(namespace.descriptor().namespaceFingerprint())), generation);
                var enrollment = database.prepareTenantProtection(capacity);
                String entity = longs.entityName();
                var participants = new PurgeParticipantManifest(entity,
                        Set.of("runtime-control-roster", "initial-native-window", "initial-source-journal"),
                        List.of(enrollment.controlParticipant(entity), enrollment.initialSourceParticipant(longs, namespace.window()),
                                new JdbcInitialBootstrapPurgeParticipant(guard, binding, entity, 2)));
                var routes = new LinkedHashMap<String, BoundedRedisWire>();
                var write = database.config().writeBehind();
                routes.put(write.streamKey(), wire);
                routes.put(write.deadLetterStreamKey(), wire);
                routes.put(write.compactionStreamKey(), wire);
                for (String key : write.activeStreamKeys()) routes.put(key, wire);
                var plan = new RedisTenantPurgePlan(binding, entity, longs.versionColumn(), guard,
                        new JdbcTenantVersionAllocator(guard, sandbox.dialect, 2),
                        new JdbcPurgeScanJournal(guard, sandbox.dialect, 2, 128, 10000, 10 * 1048576),
                        new JdbcPurgeFacadeJournal(guard, sandbox.dialect, 2, 256, 10 * 1048576),
                        new JdbcPurgeParticipantJournal(guard, 2, 1), wire, database.redisOperations(),
                        database.redisKeyStrategy(), enrollment.barrier(entity), enrollment.readFence(entity),
                        List.of(enrollment.pointProfileTarget(entity)), Set.of(longs.redisNamespace()),
                        Map.of(), routes, participants, event -> {});
                var descriptor = enrollment.descriptor(entity);
                if (!descriptor.runtimeId().equals(producer.runtimeId())) throw new AssertionError("Actual root incarnation required");
                var approval = new RedisTenantRuntimeControl.ApprovedRoster(
                        ProtectionKey.hash("producer-review:" + descriptor.runtimeId()), plan.manifestToken(),
                        descriptor.coverageToken(), List.of(descriptor.runtimeId()), List.of(generation.key()));
                var service = namespace.createBootstrap(enrollment, plan, approval, 64,
                        Set.of(namespace.window().fingerprint()));
                var request = new InitialTenantBootstrap.Request(generation, tenant, UUID.randomUUID(),
                        ProtectionKey.hash("producer-operator:" + descriptor.runtimeId()), 120);
                boolean active = false;
                for (int call = 0; call < 512; call++) {
                    var progress = service.step(request);
                    if (progress.phase() == InitialTenantBootstrap.Phase.INCOMPLETE)
                        throw new AssertionError("Real INITIAL became INCOMPLETE");
                    if (progress.phase() == InitialTenantBootstrap.Phase.ACTIVE) {
                        if (progress.sourceRows() != 0 || progress.warmedRows() != 0 || progress.highwater() != 64)
                            throw new AssertionError("Reviewed empty-source INITIAL range required");
                        active = true;
                        break;
                    }
                }
                if (!active) throw new AssertionError("INITIAL bounded continuation count exhausted");
                long controlEpoch = namespace.pendingClosure().orElseThrow().lease().epoch();
                var barrier = enrollment.barrier(entity);
                var redis = ((JedisPooledOperations) database.redisOperations()).pooledClient();
                if (!redis.hmget(RedisTenantRuntimeControl.rosterKey(database.redisKeyStrategy(), longs.redisNamespace()),
                        "state", "enrolled", "required", "pairs", "e:" + producer.runtimeId(), "manifest", "coverage")
                        .equals(List.of("SEALED", "1", "1", "1", "1", plan.manifestToken(), descriptor.coverageToken())))
                    throw new AssertionError("Real sealed one-root roster required");
                if (!redis.hmget(barrier.generationKey(generation.key()), "state", "generation", "epoch")
                        .equals(List.of("OPEN", "1", Long.toString(controlEpoch))))
                    throw new AssertionError("Real native INITIAL OPEN required");
                var allocator = new RedisTenantVersionAllocator(database.redisOperations(), database.redisKeyStrategy(),
                        barrier, longs.redisNamespace());
                var allocation = redis.hmget(allocator.allocatorKey(generation.key()), "control_epoch", "reservation_epoch", "floor", "ceiling");
                if (!Long.toString(controlEpoch).equals(allocation.get(0)) || !"0".equals(allocation.get(2)) || !"64".equals(allocation.get(3)))
                    throw new AssertionError("Actual INITIAL reservation required");
                long reservationEpoch = Long.parseLong(allocation.get(1));
                if (reservationEpoch < 1) throw new AssertionError("Actual reservation epoch required");
                try (var connection = getConnection(); var statement = connection.createStatement()) {
                    statement.setQueryTimeout(2);
                    statement.setMaxRows(2);
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM cachedb_namespace_bootstrap")) {
                        if (!rows.next() || rows.getLong(1) != 1 || rows.next()) throw new AssertionError("Real namespace birth missing");
                    }
                    try (var rows = statement.executeQuery("SELECT b.phase_name,g.gate_state,g.generation,g.lease_epoch,"
                            + "a.last_control_epoch,a.last_generation,a.highwater "
                            + "FROM cachedb_initial_bootstrap b JOIN cachedb_tenant_guard g "
                            + "ON b.scope_token=g.scope_token AND b.tenant_token=g.tenant_token "
                            + "JOIN cachedb_tenant_allocator a ON b.scope_token=a.scope_token AND b.tenant_token=a.tenant_token")) {
                        boolean present = rows.next();
                        String phase = present ? rows.getString(1) : null;
                        String gate = present ? rows.getString(2) : null;
                        long guardGeneration = present ? rows.getLong(3) : 0;
                        long sqlLeaseEpoch = present ? rows.getLong(4) : 0;
                        long allocationControlEpoch = present ? rows.getLong(5) : 0;
                        long allocationGeneration = present ? rows.getLong(6) : 0;
                        long highwater = present ? rows.getLong(7) : 0;
                        boolean multiple = present && rows.next();
                        // Bounded steps renew SQL leases; the allocator retains the activation epoch.
                        if (!present || multiple || !"ACTIVE".equals(phase) || !"OPEN".equals(gate)
                                || guardGeneration != 1 || sqlLeaseEpoch < controlEpoch
                                || allocationControlEpoch != controlEpoch || allocationGeneration != 1 || highwater != 64)
                            throw new AssertionError("Real durable INITIAL ACTIVE and SQL OPEN required"
                                    + " [present=" + present + ",multiple=" + multiple + ",phase=" + phase + ",gate=" + gate
                                    + ",generation=" + guardGeneration + ",sqlLeaseEpoch=" + sqlLeaseEpoch
                                    + ",nativeControlEpoch=" + controlEpoch + ",allocationControlEpoch=" + allocationControlEpoch
                                    + ",allocationGeneration=" + allocationGeneration + ",highwater=" + highwater + "]");
                    }
                } catch (SQLException failure) { throw new IllegalStateException(GeneratedInitialSqlSandbox.safe(failure).getMessage()); }
                if (bootstrapConnections.get() < 1) throw new AssertionError("Real bootstrap SQL required");
                // Explicit administrative closure uses the owned SQL control plane, never the foreground source.
                return new Activated(producer, controlEpoch, reservationEpoch,
                        new JdbcTenantPurgeGuard(sandbox.source, JdbcTenantPurgeDialect.POSTGRES, 2, 1,
                                Set.of(binding.fingerprint())), binding);
            }
        } finally { bootstrapThread.set(null); }
    }

    @Override public Connection getConnection() throws SQLException {
        if (bootstrapThread.get() != Thread.currentThread()) return forbidden.getConnection();
        bootstrapConnections.incrementAndGet();
        try { return sandbox.source.getConnection(); } catch (SQLException failure) { throw GeneratedInitialSqlSandbox.safe(failure); }
    }
    @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
    @Override public void close() throws Exception {
        if (bootstrapThread.get() != null) throw new AssertionError("Bootstrap must physically exit before owned cleanup");
        sandbox.close();
    }
    @Override public java.io.PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(java.io.PrintWriter writer) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 5; }
    @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("native-producer-fixture"); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("No wrapper"); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
