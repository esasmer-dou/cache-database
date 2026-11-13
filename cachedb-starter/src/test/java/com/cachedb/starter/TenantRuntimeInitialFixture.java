package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.SourcePlan;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.JdbcNamespaceBootstrapGate;
import com.reactor.cachedb.jdbc.JdbcSourceOwnerOnboarding;
import com.reactor.cachedb.jdbc.JdbcTenantPurgeDialect;
import com.reactor.cachedb.jdbc.JdbcTenantPurgeGuard;
import com.reactor.cachedb.redis.RedisInitialSourceLayout;
import com.reactor.cachedb.redis.RedisTenantProducer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/** Owned real PostgreSQL birth; later SQL is denied except in an explicit same-thread administrative test scope. */
final class TenantRuntimeInitialFixture implements DataSource, AutoCloseable {
    private static final List<String> MIGRATIONS = List.of(
            "V014__durable_write_fence.sql", "V019__tenant_protection.sql",
            "V019_1__purge_facade.sql", "V019_2__purge_list_scan_kind.sql",
            "V019_3__source_owner_onboarding.sql", "V019_4__initial_source_bootstrap.sql",
            "V019_5__namespace_bootstrap_once.sql", "V019_6__empty_tenant_activation.sql");
    private static final Set<String> TABLES = Set.of(
            "cachedb_write_fence", "cachedb_tenant_guard", "cachedb_purge_progress", "cachedb_purge_scan",
            "cachedb_purge_work_budget", "cachedb_purge_work", "cachedb_tenant_allocator",
            "cachedb_purge_participant", "cachedb_purge_facade", "cachedb_purge_call",
            "cachedb_owner_onboarding", "cachedb_initial_bootstrap", "cachedb_initial_page",
            "cachedb_namespace_bootstrap", "cachedb_empty_activation");
    final String schema;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger bootstrapConnections = new AtomicInteger();
    private final String url, user, password;
    private final boolean owner;
    private final AtomicBoolean attempted = new AtomicBoolean();
    private final AtomicReference<Thread> bootstrapThread = new AtomicReference<>();
    private final AtomicReference<Thread> administrativeThread = new AtomicReference<>();
    private boolean created;

    static TenantRuntimeInitialFixture create(EntityMetadata<?, Long> metadata) {
        var fixture = new TenantRuntimeInitialFixture(
                "cachedb_f3_46_" + UUID.randomUUID().toString().replace("-", ""), true);
        try {
            fixture.initialize(metadata);
            return fixture;
        } catch (SQLException failure) {
            var redacted = safe(failure);
            closeAfterFailedConstruction(redacted,fixture);
            throw redacted;
        } catch (RuntimeException | Error failure) {
            closeAfterFailedConstruction(failure,fixture);
            throw failure;
        }
    }

    // A killed peer never owns SQL cleanup. Its parent retains the only DROP capability.
    static TenantRuntimeInitialFixture attach(String schema) {
        return new TenantRuntimeInitialFixture(schema, false);
    }

    private TenantRuntimeInitialFixture(String schema, boolean owner) {
        if (!Boolean.getBoolean("cachedb.initial.isolatedSql"))
            throw new IllegalStateException("Explicit cachedb.initial.isolatedSql=true required");
        if (schema == null || !schema.matches("cachedb_f3_46_[0-9a-f]{32}"))
            throw new IllegalArgumentException("Owned runtime fixture schema required");
        this.schema = schema;
        this.owner = owner;
        url = setting("URL"); user = setting("USER"); password = setting("PASSWORD");
        if (!url.startsWith("jdbc:postgresql:"))
            throw new IllegalArgumentException("Explicit PostgreSQL fixture URL required");
    }

    private static String setting(String suffix) {
        String value = System.getenv("CACHEDB_INITIAL_POSTGRES_ADMIN_" + suffix);
        if (value == null || value.isEmpty()) value = System.getenv("CACHEDB_SNAPSHOT_POSTGRES_" + suffix);
        if (value == null || value.isEmpty() || value.length() > 4096)
            throw new IllegalStateException("Disposable PostgreSQL fixture setting required: " + suffix);
        return value;
    }

    private Connection connect(boolean scoped) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", user); properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "15");
        properties.setProperty("ApplicationName", "cachedb-f3-runtime-fixture");
        Connection connection = DriverManager.getConnection(url, properties);
        try {
            if (scoped) {
                connection.setSchema(schema);
                if (!schema.equals(connection.getSchema())) throw new SQLException("Owned schema not selected");
            }
            return connection;
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException ignored) { /* Preserve the primary failure. */ }
            throw failure;
        }
    }

    private void initialize(EntityMetadata<?, Long> metadata) throws SQLException {
        if (!owner || !"id".equals(metadata.idColumn()) || !"tenant".equals(metadata.tenantFieldName())
                || !"entity_version".equals(metadata.versionColumn())
                || !"java.lang.Long".equals(metadata.columnTypes().get("id"))
                || !metadata.tableName().matches("[a-z][a-z0-9_]{0,62}")
                || !(Set.copyOf(metadata.columns()).equals(Set.of("id", "tenant"))
                    || Set.copyOf(metadata.columns()).equals(Set.of("id", "tenant", "name"))))
            throw new IllegalArgumentException("Reviewed runtime fixture mapping required");
        try (var connection = connect(false)) {
            execute(connection, "CREATE SCHEMA " + schema); created = true;
        }
        try (var connection = connect(true)) {
            for (String migration : MIGRATIONS) {
                String resource = "/com/reactor/cachedb/jdbc/migrations/postgres/" + migration;
                byte[] bytes;
                try (var input = TenantRuntimeInitialFixture.class.getResourceAsStream(resource)) {
                    if (input == null) throw new AssertionError("Mandatory canonical migration missing: " + migration);
                    bytes = input.readNBytes(131073);
                } catch (java.io.IOException failure) {
                    throw new AssertionError("Canonical fixture migration could not be read");
                }
                if (bytes.length > 131072) throw new AssertionError("Fixture migration size bound");
                String sql = new String(bytes, StandardCharsets.UTF_8).lines()
                        .filter(line -> !line.stripLeading().startsWith("--"))
                        .collect(java.util.stream.Collectors.joining("\n"));
                for (String statement : sql.split(";")) {
                    String command = statement.strip();
                    if (command.isEmpty()) continue;
                    if (!command.matches("(?is)^(CREATE TABLE|CREATE INDEX|ALTER TABLE) .*"))
                        throw new AssertionError("Unexpected canonical fixture migration command");
                    execute(connection, command);
                }
            }
            var found = new HashSet<String>();
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), schema, null, new String[]{"TABLE"})) {
                while (tables.next()) {
                    if (found.size() >= 64) throw new AssertionError("Fixture metadata bound");
                    found.add(tables.getString("TABLE_NAME"));
                }
            }
            if (!found.containsAll(TABLES)) throw new AssertionError("Canonical fixture tables missing");
            requireCount(connection, "SELECT COUNT(*) FROM cachedb_purge_scan WHERE scan_kind='LIST'", 0);
            String table = metadata.tableName();
            execute(connection, "CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, tenant VARCHAR(64) COLLATE \"C\" NOT NULL, "
                    + (metadata.columns().contains("name") ? "name VARCHAR(128) NOT NULL, " : "")
                    + "entity_version BIGINT NOT NULL)");
            execute(connection, "CREATE INDEX " + table + "_tenant_id ON " + table + " (tenant,id)");
            requireCount(connection, "SELECT COUNT(*) FROM " + table, 0);
        }
        System.err.println("F3_RUNTIME_SQL_FIXTURE " + schema + " canonicalMigrations=7 emptyOwnedSource=true");
    }

    <T> RedisTenantProducer bootstrap(CacheDatabase database, EntityMetadata<T, Long> metadata,
            String tenant, int capacity) {
        if (database.dataSource() != this || !attempted.compareAndSet(false, true)
                || !bootstrapThread.compareAndSet(null, Thread.currentThread()))
            throw new AssertionError("First actual root bootstrap only");
        try (var admin = CacheTenantContext.administrative()) {
            var binding = new Binding(metadata.entityType(), metadata.redisNamespace(),
                    new SourcePlan(metadata.tableName(), metadata.tenantFieldName(), metadata.idColumn()),
                    RedisInitialSourceLayout.layoutToken(metadata, database.redisKeyStrategy(), database.config().queryIndex()),
                    10000, 128, 1048576, true);
            var guard = new JdbcTenantPurgeGuard(this, JdbcTenantPurgeDialect.POSTGRES, 2, 1, Set.of(binding.fingerprint()));
            var generation = new Generation(binding.key(tenant), 1);
            guard.provisionClosed(generation);
            try (var namespace = database.prepareInitialNamespace(metadata, binding, guard,
                    JdbcSourceOwnerOnboarding.longIds(), 1L, 1L, 1, 65536, 4096, 1048576, capacity)) {
                var gate = new JdbcNamespaceBootstrapGate(guard, Set.of(namespace.descriptor().namespaceFingerprint()));
                var producer = namespace.bootstrap(gate, generation);
                try (var connection = getConnection()) {
                    requireCount(connection, "SELECT COUNT(*) FROM cachedb_namespace_bootstrap", 1);
                } catch (SQLException failure) { throw safe(failure); }
                if (bootstrapConnections.get() < 1) throw new AssertionError("Real SQL bootstrap required");
                System.err.println("F3_RUNTIME_NAMESPACE_BIRTH " + schema + " bootstrapConnections=" + bootstrapConnections.get());
                return producer;
            }
        } finally { bootstrapThread.set(null); }
    }

    AutoCloseable administrativeSql() {
        if (!CacheTenantContext.isAdministrative() || bootstrapThread.get()!=null
                || !administrativeThread.compareAndSet(null,Thread.currentThread()))
            throw new AssertionError("Explicit single-thread administrative SQL scope required");
        Thread ownerThread=Thread.currentThread();
        return ()->{
            if (Thread.currentThread()!=ownerThread || !administrativeThread.compareAndSet(ownerThread,null))
                throw new AssertionError("Administrative SQL scope ownership changed");
        };
    }

    @Override public Connection getConnection() throws SQLException {
        if (administrativeThread.get()==Thread.currentThread() && CacheTenantContext.isAdministrative()) {
            try { return connect(true); } catch (SQLException failure) { throw safe(failure); }
        }
        if (bootstrapThread.get() != Thread.currentThread()) {
            calls.incrementAndGet();
            throw new AssertionError("No SQL permitted after real namespace birth");
        }
        bootstrapConnections.incrementAndGet();
        try { return connect(true); } catch (SQLException failure) { throw safe(failure); }
    }
    @Override public Connection getConnection(String username, String credential) throws SQLException { return getConnection(); }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(15); statement.executeUpdate(sql); }
    }
    private static void requireCount(Connection connection, String sql, long expected) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            try (var rows = statement.executeQuery(sql)) {
                if (!rows.next() || rows.getLong(1) != expected || rows.next())
                    throw new AssertionError("Owned canonical fixture state mismatch");
            }
        }
    }
    private static IllegalStateException safe(SQLException failure) {
        return new IllegalStateException("Private PostgreSQL fixture failure [state=" + failure.getSQLState()
                + ",code=" + failure.getErrorCode() + "]");
    }
    static void closeAfterFailedConstruction(Throwable primary,AutoCloseable... resources) {
        for (var resource : resources) {
            if (resource == null) continue;
            try { resource.close(); }
            catch (Exception | Error cleanup) {
                if (primary != cleanup) primary.addSuppressed(cleanup);
            }
        }
    }

    @Override public void close() {
        if (!owner || !created) return;
        if (bootstrapThread.get() != null || administrativeThread.get()!=null)
            throw new AssertionError("Administrative SQL must physically exit before cleanup");
        try (var connection = connect(false)) {
            execute(connection, "DROP SCHEMA " + schema + " CASCADE"); created = false;
        } catch (SQLException failure) { throw safe(failure); }
    }
    @Override public java.io.PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(java.io.PrintWriter writer) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 5; }
    @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("f3-runtime-fixture"); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("Not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
