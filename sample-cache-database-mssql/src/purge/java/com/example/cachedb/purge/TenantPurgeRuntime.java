package com.example.cachedb.purge;

import com.example.cachedb.sample.SampleRedisAuth;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.params.SetParams;

/** Disposable, fixed-table demonstration. Security rows are deliberately never reset or reopened. */
final class TenantPurgeRuntime implements AutoCloseable {
    static final String ENTITY = "TenantPurgeRow", NS = "sample-purge-rows";
    private static final JdbcTenantPurgeDialect DIALECT = JdbcTenantPurgeDialect.MSSQL;
    private final DataSource source;
    final String tenant, foreignTenant, prefix;
    private final AtomicReference<Throwable> controlFailure = new AtomicReference<>();
    private JedisPooled redis, peerRedis;
    private BoundedRedisWire wire;
    private CacheDatabase database, peer;
    private CacheTenantRuntimeEnrollment primary, secondary;
    private CacheInitialNamespace<TenantPurgeRow, Long> namespace;
    private RedisTenantPurgePlan plan, peerPlan;
    private RedisTenantRuntimeControl.ApprovedRoster approval;
    private ScheduledExecutorService controls;
    private JdbcTenantPurgeGuard guard;
    private Binding binding;
    private Generation generation;
    private EntityCodec<TenantPurgeRow> codec;
    private CacheDataProtection facade;
    private CacheDataProtection.Continuation continuation;
    private UUID operation;
    private CacheDataProtection.Result last;
    private String foreignSql, foreignRedis, sentinel;
    private boolean sealed;
    private volatile boolean ready;

    @SuppressWarnings("unchecked")
    TenantPurgeRuntime(DataSource source, Environment env) {
        this.source = Objects.requireNonNull(source);
        String run = TenantPurgeOptIn.require(env);
        tenant = "purge-" + run + "-a"; foreignTenant = "purge-" + run + "-b";
        prefix = "sample-purge-" + run;
        InitializationStage stage = InitializationStage.REDIS;
        try {
            URI uri = URI.create(env.getRequiredProperty("SAMPLE_PURGE_REDIS_URI"));
            // This demo deliberately supports only an isolated, non-TLS, DB0 fixture.
            if (!"redis".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()) || "/0".equals(uri.getPath())))
                throw new IllegalArgumentException("Standalone DB0 fixture URI required");
            int port = uri.getPort() < 0 ? 6379 : uri.getPort();
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid fixture port");
            var client = SampleRedisAuth.purgeClientConfig(env.getProperty("SAMPLE_PURGE_REDIS_PASSWORD"));
            redis = new JedisPooled(new HostAndPort(uri.getHost(), port), client);
            peerRedis = new JedisPooled(new HostAndPort(uri.getHost(), port), client);
            redis.getPool().setMaxTotal(4); peerRedis.getPool().setMaxTotal(4);
            redis.getPool().setMaxWait(java.time.Duration.ofSeconds(3));
            peerRedis.getPool().setMaxWait(java.time.Duration.ofSeconds(3));
            char[] password = client.getPassword() == null ? null : client.getPassword().toCharArray();
            try {
                wire = new BoundedRedisWire(new InetSocketAddress(uri.getHost(), port), null, null,
                        null, password, 0, 2000, 2000, 1);
            } finally { if (password != null) Arrays.fill(password, '\0'); }
            var policy = new CachePolicy(8, 8, false, 0, 5);
            String functions = "purge_" + run;
            var config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .readThrough(ReadThroughConfig.builder().mode(ReadThroughMode.REDIS_ONLY).build())
                    .resourceLimits(ResourceLimits.builder().defaultCachePolicy(policy).maxRegisteredEntities(1).build())
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                    .redisFunctions(RedisFunctionsConfig.builder().libraryName(functions)
                            .upsertFunctionName(functions + "_upsert").deleteFunctionName(functions + "_delete")
                            .compactionCompleteFunctionName(functions + "_complete").build())
                    .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix + ":write")
                            .deadLetterStreamKey(prefix + ":dlq").compactionStreamKey(prefix + ":compact").build())
                    .build();
            var material = KeyMaterial.fromBase64("purge-v1", env.getRequiredProperty("SAMPLE_ENCRYPTION_AES_KEY"),
                    env.getRequiredProperty("SAMPLE_ENCRYPTION_HMAC_KEY"));
            var keys = new StaticKeyProvider(Map.of("sample-purge", List.of(material)),
                    Map.of("sample-purge", "purge-v1"));
            var options = CacheRuntimeOptions.builder().keyProvider(keys).build();
            database = new CacheDatabase(redis, source, config, options);
            peer = new CacheDatabase(peerRedis, source, config, options);
            stage = InitializationStage.RUNTIME;
            database.start(); peer.start();
            database.register(TenantPurgeRowCacheBinding.METADATA, TenantPurgeRowCacheBinding.CODEC, policy);
            peer.register(TenantPurgeRowCacheBinding.METADATA, TenantPurgeRowCacheBinding.CODEC, policy);
            codec = (EntityCodec<TenantPurgeRow>) database.entityRegistry().find(ENTITY).orElseThrow().codec();
            stage = InitializationStage.SOURCE_SEED;
            seedFreshSource();
            binding = new Binding(TenantPurgeRow.class, NS, new SourcePlan("sample_purge_rows", "tenant_id", "id"),
                    RedisInitialSourceLayout.layoutToken(TenantPurgeRowCacheBinding.METADATA,
                            database.redisKeyStrategy(), config.queryIndex()), 10000, 128, 1048576, true);
            guard = new JdbcTenantPurgeGuard(source, DIALECT, 2, 1, Set.of(binding.fingerprint()));
            generation = new Generation(binding.key(tenant), 1);
            try (var admin = CacheTenantContext.administrative()) {
                stage = InitializationStage.SQL_GUARD;
                guard.provisionClosed(generation);
                var ids = JdbcSourceOwnerOnboarding.longIds();
                stage = InitializationStage.NAMESPACE;
                namespace = database.prepareInitialNamespace(TenantPurgeRowCacheBinding.METADATA,
                        binding, guard, ids, 1L, 2L, 8, 65536, 16384, 1048576, 8);
                stage = InitializationStage.NAMESPACE_BOOTSTRAP;
                namespace.bootstrap(new JdbcNamespaceBootstrapGate(guard,
                        Set.of(namespace.descriptor().namespaceFingerprint())), generation);
                stage = InitializationStage.PARTICIPANTS;
                peer.configureTenantProducer(TenantPurgeRowCacheBinding.METADATA, 8);
                primary = database.prepareTenantProtection(8); secondary = peer.prepareTenantProtection(8);
                plan = plan(database, primary, namespace.window());
                var peerCodec = (EntityCodec<TenantPurgeRow>) peer.entityRegistry().find(ENTITY).orElseThrow().codec();
                var peerWindow = new InitialSourceHotWindow<>(binding, TenantPurgeRowCacheBinding.METADATA,
                        peerCodec, ids, ids.formatToken(), 1L, 2L, 8, 65536, 16384, 1048576,
                        InitialSourceHotWindow.Profile.POINT_NO_QUOTA);
                peerPlan = plan(peer, secondary, peerWindow);
                if (!plan.manifestToken().equals(peerPlan.manifestToken()))
                    throw new IllegalStateException("Actual root manifests disagree");
                approval = new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("sample-review:" + run),
                        plan.manifestToken(), primary.descriptor(ENTITY).coverageToken(),
                        List.of(primary.descriptor(ENTITY).runtimeId(), secondary.descriptor(ENTITY).runtimeId()),
                        List.of(generation.key()));
                stage = InitializationStage.INITIAL_PLAN;
                var initial = namespace.createBootstrap(primary, plan, approval, 64,
                        Set.of(namespace.window().fingerprint()));
                var request = new InitialTenantBootstrap.Request(generation, tenant, UUID.randomUUID(),
                        ProtectionKey.hash("sample-operator:" + run), 120);
                controls = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "sample-purge-control"); t.setDaemon(true); return t;
                });
                controls.scheduleWithFixedDelay(this::pollPeer, 0, 20, TimeUnit.MILLISECONDS);
                stage = InitializationStage.INITIAL_EXECUTION;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                boolean active = false;
                for (int step = 0; step < 512 && System.nanoTime() < deadline; step++) {
                    requireControl();
                    var progress = initial.step(request);
                    if (progress.phase() == InitialTenantBootstrap.Phase.INCOMPLETE)
                        throw new IllegalStateException("Native INITIAL incomplete");
                    if (progress.phase() == InitialTenantBootstrap.Phase.ACTIVE) {
                        if (progress.warmedRows() != 2) throw new IllegalStateException("Native hot window differs");
                        active = true; break;
                    }
                }
                if (!active) throw new IllegalStateException("Native INITIAL budget exhausted");
                facade = database.configureDataProtection(List.of(plan), 1);
            }
            stage = InitializationStage.FOREIGN_SENTINEL;
            sentinel = prefix + ":foreign:sentinel";
            foreignRedis = codec.toRedisValue(new TenantPurgeRow(8L, foreignTenant, "purge-demo-value-8"));
            if (!"OK".equals(redis.set(sentinel, foreignRedis, SetParams.setParams().nx())))
                throw new IllegalStateException("Fresh Redis sentinel required");
            foreignSql = foreignSqlSnapshot();
            ready = true;
        } catch (Exception failure) {
            close();
            throw initializationFailure(stage, failure);
        }
    }

    enum InitializationStage {
        REDIS, RUNTIME, SOURCE_SEED, SQL_GUARD, NAMESPACE, NAMESPACE_BOOTSTRAP,
        PARTICIPANTS, INITIAL_PLAN, INITIAL_EXECUTION, FOREIGN_SENTINEL
    }

    static IllegalStateException initializationFailure(InitializationStage stage, Exception failure) {
        String code = "UNEXPECTED";
        if (failure instanceof ProtectionException protection) code = protection.code().name();
        else if (failure instanceof SQLException sql) {
            String state = sql.getSQLState();
            code = "SQL_" + (state != null && state.matches("[A-Za-z0-9]{5}") ? state : "UNKNOWN")
                    + "_" + sql.getErrorCode();
        }
        // Never attach the original exception: its message/cause can contain credentials or bind values.
        return new IllegalStateException("Purge demo initialization failed [stage=" + stage.name()
                + ", code=" + code + "]; retain the disposable fixture for inspection");
    }

    private void pollPeer() {
        try (var admin = CacheTenantContext.administrative()) {
            if (!sealed) {
                var pending = namespace.pendingClosure();
                if (pending.isPresent()) {
                    secondary.sealClosed(peerPlan, approval, List.of(pending.orElseThrow())); sealed = true;
                }
            }
            if (sealed) secondary.pollControl(1, 1000);
        } catch (Throwable failure) { controlFailure.compareAndSet(null, failure); }
    }

    private void requireControl() {
        if (controlFailure.get() != null) throw new IllegalStateException("Actual peer control failed");
    }

    private RedisTenantPurgePlan plan(CacheDatabase root, CacheTenantRuntimeEnrollment enrollment,
            InitialSourceHotWindow<TenantPurgeRow, Long> window) {
        var participants = new PurgeParticipantManifest(ENTITY,
                Set.of("runtime-control-roster", "initial-native-window", "initial-source-journal"),
                List.of(enrollment.controlParticipant(ENTITY),
                        enrollment.initialSourceParticipant(TenantPurgeRowCacheBinding.METADATA, window),
                        new JdbcInitialBootstrapPurgeParticipant(guard, binding, ENTITY, 2)));
        var streams = new LinkedHashMap<String, BoundedRedisWire>();
        var write = root.config().writeBehind();
        streams.put(write.streamKey(), wire); streams.put(write.deadLetterStreamKey(), wire);
        streams.put(write.compactionStreamKey(), wire);
        for (String stream : write.activeStreamKeys()) streams.put(stream, wire);
        return new RedisTenantPurgePlan(binding, ENTITY, "entity_version", guard,
                new JdbcTenantVersionAllocator(guard, DIALECT, 2),
                new JdbcPurgeScanJournal(guard, DIALECT, 2, 128, 10000, 10 * 1048576),
                new JdbcPurgeFacadeJournal(guard, DIALECT, 2, 256, 10 * 1048576),
                new JdbcPurgeParticipantJournal(guard, 2, 1), wire, root.redisOperations(), root.redisKeyStrategy(),
                enrollment.barrier(ENTITY), enrollment.readFence(ENTITY),
                List.of(enrollment.pointProfileTarget(ENTITY)), Set.of(NS), Map.of(), streams, participants, event -> {});
    }

    private void seedFreshSource() throws SQLException {
        try (var c = source.getConnection()) {
            try (var q = c.prepareStatement("SELECT COUNT(*) FROM sample_purge_rows")) {
                q.setQueryTimeout(2);
                try (var rows = q.executeQuery()) {
                    rows.next(); if (rows.getLong(1) != 0) throw new IllegalStateException("Empty disposable table required");
                }
            }
            c.setAutoCommit(false);
            try (var q = c.prepareStatement(
                    "INSERT INTO sample_purge_rows(id,tenant_id,secret,entity_version) VALUES(?,?,?,?)")) {
                q.setQueryTimeout(2);
                for (long id = 1; id <= 8; id++) {
                    var row = new TenantPurgeRow(id, id == 8 ? foreignTenant : tenant, "purge-demo-value-" + id);
                    q.setLong(1, id); q.setString(2, row.tenant());
                    q.setString(3, codec.toSourceColumns(row).get("secret").toString());
                    q.setLong(4, 100 + id); q.addBatch();
                }
                q.executeBatch(); c.commit();
            } catch (Exception failure) { c.rollback(); throw failure; }
        }
    }

    void deliver(long id, Consumer<Map<String, Object>> writeAndFlush) {
        if (id < 1 || id > 7) throw new IllegalArgumentException("Owned fixed ID required");
        requireControl();
        primary.deliver(TenantPurgeRowCacheBinding.METADATA, tenant,
                repository -> Map.<String, Object>of("id", id, "present", repository.findById(id).isPresent()),
                writeAndFlush);
    }

    synchronized PurgeStatus purge(UUID requested) {
        if (!ready) throw new IllegalStateException("Native INITIAL not active");
        requireControl();
        if (operation != null && !operation.equals(requested))
            throw new IllegalArgumentException("Only the original operation may continue");
        operation = Objects.requireNonNull(requested);
        if (last != null && last.status() == CacheDataProtection.Status.COMPLETE) return status();
        var options = new PurgeOptions(operation, tenant, ProtectionKey.hash("sample-admin:" + prefix),
                true, 8, 120, 3000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
        try (var admin = CacheTenantContext.administrative()) {
            for (int call = 0; call < 128 && System.nanoTime() < deadline; call++) {
                requireControl();
                try {
                    last = facade.purgeTenant(TenantPurgeRow.class, tenant, options, continuation);
                } catch (RuntimeException | Error failure) {
                    var abandoned = continuation;
                    continuation = null;
                    // Release ownership only; recovery needs an explicit request with the same operation.
                    if (abandoned != null) {
                        try { facade.abandon(abandoned); }
                        catch (RuntimeException | Error cleanup) {
                            if (cleanup != failure) failure.addSuppressed(cleanup);
                        }
                    }
                    throw failure;
                }
                continuation = last.continuation();
                if (last.status() == CacheDataProtection.Status.COMPLETE) return status();
                if (last.status() != CacheDataProtection.Status.RUNNING)
                    throw new IllegalStateException("Purge incomplete; preserve CLOSED state and original operation");
            }
        }
        throw new IllegalStateException("Purge budget exhausted; preserve CLOSED state and original operation");
    }

    synchronized PurgeStatus status() {
        requireControl();
        try (var c = source.getConnection(); var q = c.prepareStatement(
                "SELECT generation,gate_state FROM cachedb_tenant_guard WHERE scope_token=? AND tenant_token=?")) {
            q.setQueryTimeout(2); q.setString(1, generation.key().scopeToken());
            q.setString(2, generation.key().tenantToken());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Durable gate absent");
                String gateKey = primary.barrier(ENTITY).generationKey(generation.key());
                String redisGate = redis.hget(gateKey, "state");
                long redisGeneration = Long.parseLong(redis.hget(gateKey, "generation"));
                long hot = 0;
                for (long id = 1; id <= 2; id++) if (redis.exists(entityKey(id))) hot++;
                int roots = Set.of(primary.descriptor(ENTITY).runtimeId(), secondary.descriptor(ENTITY).runtimeId()).size();
                return new PurgeStatus(last == null ? "ACTIVE" : last.status().name(), roots, r.getString(2),
                        r.getLong(1), redisGate, redisGeneration, ownedSqlRows(), hot,
                        coldRedisAbsent(), Objects.equals(foreignSql, foreignSqlSnapshot()),
                        Objects.equals(foreignRedis, redis.get(sentinel)),
                        last == null ? 0 : last.receipt().sourceRows(),
                        last == null ? 0 : last.receipt().redisRows(),
                        last != null && last.receipt().retainsSecurityMetadata());
            }
        } catch (SQLException failure) { throw new IllegalStateException("Purge status unavailable"); }
    }

    record PurgeStatus(String phase, int actualRoots, String sqlGate, long sqlGeneration,
            String redisGate, long redisGeneration, long ownedSqlRows, long hotOwnedRows,
            boolean coldAbsentFromRedis, boolean foreignSqlPreserved, boolean foreignRedisPreserved,
            long purgedSqlRows, long purgedRedisRows, boolean securityMetadataRetained) {}

    long ownedSqlRows() throws SQLException {
        try (var c = source.getConnection(); var q = c.prepareStatement(
                "SELECT COUNT(*) FROM sample_purge_rows WHERE tenant_id=?")) {
            q.setQueryTimeout(2); q.setString(1, tenant);
            try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    String sourceSecret(long id) throws SQLException {
        if (id < 1 || id > 8) throw new IllegalArgumentException("Fixed ID required");
        try (var c = source.getConnection(); var q = c.prepareStatement("SELECT secret FROM sample_purge_rows WHERE id=?")) {
            q.setQueryTimeout(2); q.setLong(1, id);
            try (var r = q.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }
    String entityKey(long id) { return database.redisKeyStrategy().entityKey(NS, id); }
    String foreignSqlSnapshot() throws SQLException {
        try (var c = source.getConnection(); var q = c.prepareStatement(
                "SELECT tenant_id,secret,entity_version FROM sample_purge_rows WHERE id=8")) {
            q.setQueryTimeout(2);
            try (var r = q.executeQuery()) {
                return r.next() ? r.getString(1) + "|" + r.getString(2) + "|" + r.getBigDecimal(3).toPlainString() : null;
            }
        }
    }
    String redisValue(long id) { return redis.get(entityKey(id)); }
    private boolean coldRedisAbsent() {
        for (long id = 3; id <= 7; id++) if (redis.exists(entityKey(id))) return false;
        return true;
    }
    String foreignRedisValue() { return redis.get(sentinel); }
    boolean ownedRedisRemoved() {
        var keys = database.redisKeyStrategy();
        for (long id = 1; id <= 7; id++) {
            if (redis.exists(keys.entityKey(NS, id), keys.tenantHotOwnerKey(NS, id),
                    keys.versionKey(NS, id), keys.versionKey(NS, id) + ":tenant", keys.indexMetaKey(NS, id)) > 0) return false;
        }
        return true;
    }
    void assertSourceWriteClosed() throws SQLException {
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                guard.authorizeWrite(c, generation);
            } catch (SQLException | RuntimeException | Error failure) {
                try { c.rollback(); }
                catch (SQLException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
            c.rollback();
        }
    }

    @Override public void close() {
        ready = false;
        if (controls != null) {
            controls.shutdownNow();
            try { controls.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        // Never FLUSH, drop a table, delete a function or reopen a generation during cleanup.
        for (AutoCloseable resource : new AutoCloseable[]{peer, database, wire, peerRedis, redis})
            if (resource != null) try { resource.close(); } catch (Exception ignored) {}
    }
}

