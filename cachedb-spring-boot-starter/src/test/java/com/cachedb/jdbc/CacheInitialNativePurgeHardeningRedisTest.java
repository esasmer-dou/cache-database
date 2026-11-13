package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.jdbc.CacheInitialNativeBridgeProvidersRedisTest.controlPlan;

import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Phase;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real provider source, actual guard lease, native Redis and the public purge facade. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@EnabledIfSystemProperty(named = "cachedb.initial.isolatedSql", matches = "true")
@Timeout(120)
class CacheInitialNativePurgeHardeningRedisTest {
    private static final String NS = "native_rows", ENTITY = "NativeInitialRow";

    @Test void postgresPurgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant() throws Exception {
        purgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant("postgres");
    }

    @Test void mssqlPurgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant() throws Exception {
        purgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant("mssql");
    }

    @Test void oraclePurgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant() throws Exception {
        purgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant("oracle");
    }

    private static void purgeResumesAfterNativeEraseBeforeCheckpointCommitAndPreservesForeignTenant(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative();
                var f = new CacheInitialNativeBridgeProvidersRedisTest.Fixture(provider, false, true)) {
            var bound = activate(f);
            var keys = f.database.redisKeyStrategy();
            var foreign = new CacheInitialNativeBridgeProvidersRedisTest.Row(3L, "b",
                    CacheInitialNativeBridgeProvidersRedisTest.SECRET + 3);
            f.redis.set(keys.entityKey(NS, 3L), f.bound.toRedisValue(foreign));
            f.redis.set(keys.versionKey(NS, 3L), "18000");
            f.redis.set(keys.versionKey(NS, 3L) + ":tenant", "b");
            var indexes = new RedisQueryIndexManager<>(f.database.redisOperations(), f.metadata, f.bound,
                    keys, f.database.entityRegistry(), f.database.config().queryIndex(), RelationConfig.defaults(),
                    new QueryEvaluator(), null);
            indexes.reindex(foreign);
            var foreignBefore = foreignRedis(f);
            assertEquals(foreign, f.bound.fromRedisValue(foreignBefore.values().get(keys.entityKey(NS, 3L))));
            assertTrue(foreignBefore.values().get(keys.entityKey(NS, 3L)).contains("~E1:k2:"));
            assertTrue(f.originalSource.get(3L).contains("~E1:k1:"));
            assertEquals("18000", foreignBefore.values().get(keys.versionKey(NS, 3L)));
            assertEquals("b", foreignBefore.values().get(keys.versionKey(NS, 3L) + ":tenant"));
            assertTrue(foreignBefore.metadata().keySet().containsAll(Set.of("tenant_id", "secret")));
            assertTrue(foreignBefore.allMember());
            foreignBefore.exactMembers().values().forEach(members -> assertEquals(Set.of("3"), members));
            var hotBefore = Map.of(1L, f.redis.get(keys.entityKey(NS, 1L)),
                    2L, f.redis.get(keys.entityKey(NS, 2L)));
            assertFalse(f.redis.exists(keys.entityKey(NS, 99L)), "Source row 99 must remain cold");
            assertEquals(f.originalSource, f.sourceSnapshot());
            var options = options(f);
            CacheDataProtection facade = new CacheDataProtection(f.database.entityRegistry(), List.of(bound.plan()), 1);
            CacheDataProtection.Continuation continuation = null;
            try {
                var journal = new JdbcPurgeFacadeJournal(f.guard, f.sandbox.dialect, 2, 256, 10 * 1048576);
                boolean identities = false;
                for (int call = 0; call < 1024; call++) {
                    var result = facade.purgeTenant(CacheInitialNativeBridgeProvidersRedisTest.Row.class, "a", options, continuation);
                    assertEquals(CacheDataProtection.Status.RUNNING, result.status());
                    continuation = result.continuation();
                    assertNotNull(continuation);
                    if (journal.get(continuation.lease(), "control").cursor().equals("IDENTITIES")) {
                        identities = true;
                        break;
                    }
                }
                assertTrue(identities, "Purge must reach the native destructive window");
                var interruptedLease = continuation.lease();
                var before = f.guard.load(interruptedLease);
                assertEquals(0, before.redisRows());
                assertEquals(0, before.sourceRows());
                assertNull(identityCheckpoint(f), "ERASE must not have run yet");
                assertEquals(hotBefore, Map.of(1L, f.redis.get(keys.entityKey(NS, 1L)),
                        2L, f.redis.get(keys.entityKey(NS, 2L))));
                long first = List.of(1L, 2L).stream()
                        .min(Comparator.comparing(id -> interruptedLease.key().objectToken(id.toString()))).orElseThrow();
                String token = interruptedLease.key().objectToken(Long.toString(first));
                var keyProbe = new RedisKeyStrategy(f.prefix) {
                    String prefix() { return namespacePrefix(NS); }
                };
                assertEquals(keys.getClass(), RedisKeyStrategy.class, "Fixture requires the standard key layout");
                assertEquals(keys.entityKey(NS, first), keyProbe.entityKey(NS, first));
                String receiptKey = keyProbe.prefix() + ":purge:" + interruptedLease.key().tenantToken()
                        + ":" + options.operationId() + ":native:" + token;
                assertFalse(f.redis.exists(receiptKey));
                f.faults.arm("UPDATE cachedb_purge_participant SET revision=", false);
                {
                    var failedFacade = facade;
                    var failedContinuation = continuation;
                    facade = null;
                    continuation = null;
                    // Native Lua has committed its erasure/receipt; the following SQL commit rolls back.
                    // This is a destructive-window failure simulation, not process death or SIGKILL.
                    var failure = assertThrows(ProtectionException.class, () -> failedFacade.purgeTenant(
                            CacheInitialNativeBridgeProvidersRedisTest.Row.class, "a", options, failedContinuation));
                    assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE, failure.code());
                }
                f.faults.requireConsumed();
                assertEquals(1, f.faults.fired.get());
                for (String key : List.of(keys.entityKey(NS, first), keys.versionKey(NS, first),
                        keys.versionKey(NS, first) + ":tenant", keys.indexMetaKey(NS, first)))
                    assertFalse(f.redis.exists(key), "Real native owned erasure must precede the failed SQL commit");
                String nativeReceipt = f.redis.get(receiptKey);
                assertNotNull(nativeReceipt, "Durable native receipt must survive the failed checkpoint");
                long removed = Long.parseLong(nativeReceipt.split("\\|", -1)[2]);
                assertTrue(removed > 0);
                assertEquals(new JdbcPurgeParticipantJournal.Checkpoint(0, PurgeParticipant.State.MORE, "", 0, 0, ""),
                        identityCheckpoint(f), "SQL cursor and counters must roll back together");
                assertEquals(before, storedProgress(f));
                assertEquals(f.originalSource, f.sourceSnapshot(), "Source deletion must not have started");
                assertEquals(foreignBefore, foreignRedis(f));
                long remaining = first == 1L ? 2L : 1L;
                assertEquals(hotBefore.get(remaining), f.redis.get(keys.entityKey(NS, remaining)));

                var replayPlan = controlPlan(f, f.database, bound.enrollment(), bound.namespace().window());
                assertEquals(bound.plan().manifestToken(), replayPlan.manifestToken());
                facade = new CacheDataProtection(f.database.entityRegistry(), List.of(replayPlan), 1);
                CacheDataProtection.Result replay = null;
                JdbcPurgeParticipantJournal.Checkpoint replayed = null;
                for (int call = 0; call < 1024; call++) {
                    replay = facade.purgeTenant(CacheInitialNativeBridgeProvidersRedisTest.Row.class, "a", options, continuation);
                    assertEquals(CacheDataProtection.Status.RUNNING, replay.status());
                    continuation = replay.continuation();
                    assertNotNull(continuation);
                    replayed = identityCheckpoint(f);
                    assertNotNull(replayed);
                    if (replayed.revision() != 0) break;
                    assertEquals(before, storedProgress(f), "A pending re-drain cannot advance destructive work");
                }
                assertTrue(continuation.lease().epoch() > interruptedLease.epoch());
                assertEquals(options.operationId(), continuation.lease().operationId());
                assertNotNull(replayed);
                assertEquals(1, replayed.revision());
                assertEquals("e:" + token, replayed.cursor());
                assertEquals(1, replayed.examined());
                assertEquals(removed, replayed.removed());
                assertEquals(1, replay.receipt().redisRows(), "Replay must count the erased identity exactly once");
                assertEquals(before.redisKeys() + removed, replay.receipt().redisKeys());
                assertEquals(nativeReceipt, f.redis.get(receiptKey));
                assertEquals(hotBefore.get(remaining), f.redis.get(keys.entityKey(NS, remaining)),
                        "Replay must revisit the erased identity, not skip to the next one");
                assertEquals(foreignBefore, foreignRedis(f));
                assertEquals(f.originalSource, f.sourceSnapshot());
                CacheDataProtection.Result result = replay;
                for (int call = 0; call < 1024; call++) {
                    result = facade.purgeTenant(CacheInitialNativeBridgeProvidersRedisTest.Row.class, "a", options, continuation);
                    continuation = result.continuation();
                    assertNotEquals(CacheDataProtection.Status.INCOMPLETE, result.status());
                    assertNotEquals(CacheDataProtection.Status.MIGRATION_REQUIRED, result.status());
                    assertEquals(foreignBefore, foreignRedis(f));
                    assertEquals(f.originalSource.get(3L), f.sourceSnapshot().get(3L));
                    if (result.status() == CacheDataProtection.Status.COMPLETE) break;
                }
                assertEquals(CacheDataProtection.Status.COMPLETE, result.status());
                assertEquals(Stage.COMPLETE, result.receipt().stage());
                assertEquals(2, result.receipt().redisRows(), "Native replay must not double-count");
                assertEquals(3, result.receipt().sourceRows(), "Both hot rows and cold row 99 must be erased");
                assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
                for (long id : List.of(1L, 2L, 99L)) {
                    for (String key : List.of(keys.entityKey(NS, id), keys.versionKey(NS, id),
                            keys.versionKey(NS, id) + ":tenant", keys.indexMetaKey(NS, id),
                            keys.tombstoneKey(NS, id), keys.durabilityKey(NS, id), keys.tenantHotOwnerKey(NS, id)))
                        assertFalse(f.redis.exists(key));
                    assertFalse(f.redis.sismember(keys.indexAllKey(NS), Long.toString(id)));
                }
                assertFalse(f.redis.exists(receiptKey), "Final receipt GC must remove native replay receipts");
                assertEquals("CLOSED", f.redis.hget(bound.enrollment().barrier(ENTITY)
                        .generationKey(f.generation.key()), "state"));
                try (var c = f.source.getConnection()) {
                    c.setAutoCommit(false);
                    assertThrows(SQLException.class, () -> f.guard.authorizeWrite(c, f.generation));
                    c.rollback();
                }
                var repeated = facade.purgeTenant(CacheInitialNativeBridgeProvidersRedisTest.Row.class, "a", options);
                assertEquals(CacheDataProtection.Status.COMPLETE, repeated.status());
                assertEquals(result.receipt(), repeated.receipt());
                assertEquals(result.evidence(), repeated.evidence());
                assertEquals(foreignBefore, foreignRedis(f));
                assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot());
                var generation = f.redis.hgetAll(bound.enrollment().barrier(ENTITY).generationKey(f.generation.key()));
                assertEquals("CLOSED", generation.get("state"));
                assertTrue(generation.keySet().stream().noneMatch(field -> field.startsWith("i:")));
                assertEquals(1, count(f, "cachedb_namespace_bootstrap"));
                assertEquals(0, count(f, "cachedb_initial_bootstrap"));
                assertEquals(0, count(f, "cachedb_initial_page"));
                assertEquals(1, count(f, "cachedb_tenant_guard"));
                assertEquals(1, count(f, "cachedb_tenant_allocator"));
            } finally {
                if (continuation != null) facade.abandon(continuation);
            }
        }
    }

    private record ForeignRedis(Map<String, String> values, Map<String, String> metadata,
            Map<String, Set<String>> exactMembers, boolean allMember) {}

    private static ForeignRedis foreignRedis(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        var keys = f.database.redisKeyStrategy();
        var values = new LinkedHashMap<String, String>();
        for (String key : List.of(keys.entityKey(NS, 3L), keys.versionKey(NS, 3L), keys.versionKey(NS, 3L) + ":tenant"))
            values.put(key, f.redis.get(key));
        var metadata = f.redis.hgetAll(keys.indexMetaKey(NS, 3L));
        var members = new LinkedHashMap<String, Set<String>>();
        metadata.forEach((column, value) -> {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            String key = keys.indexExactKey(NS, column, encoded);
            members.put(key, f.redis.smembers(key));
        });
        return new ForeignRedis(values, metadata, members, f.redis.sismember(keys.indexAllKey(NS), "3"));
    }

    private static JdbcPurgeParticipantJournal.Checkpoint identityCheckpoint(
            CacheInitialNativeBridgeProvidersRedisTest.Fixture f) throws SQLException {
        try (var c = f.source.getConnection(); var q = c.prepareStatement(
                "SELECT revision,step_state,checkpoint_value,examined_count,removed_count,evidence_token"
                        + " FROM cachedb_purge_participant WHERE scope_token=? AND tenant_token=?"
                        + " AND participant_id='native-redis-identities' AND phase_name='ERASE'")) {
            q.setQueryTimeout(2);
            q.setString(1, f.generation.key().scopeToken()); q.setString(2, f.generation.key().tenantToken());
            try (var rows = q.executeQuery()) {
                if (!rows.next()) return null;
                var checkpoint = new JdbcPurgeParticipantJournal.Checkpoint(rows.getLong(1),
                        PurgeParticipant.State.valueOf(rows.getString(2)), Objects.requireNonNullElse(rows.getString(3), ""),
                        rows.getLong(4), rows.getLong(5), Objects.requireNonNullElse(rows.getString(6), ""));
                assertFalse(rows.next());
                return checkpoint;
            }
        }
    }

    private static Progress storedProgress(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) throws Exception {
        try (var c = f.source.getConnection(); var q = c.prepareStatement(
                "SELECT payload FROM cachedb_purge_progress WHERE scope_token=? AND tenant_token=?")) {
            q.setQueryTimeout(2);
            q.setString(1, f.generation.key().scopeToken()); q.setString(2, f.generation.key().tenantToken());
            try (var rows = q.executeQuery()) {
                assertTrue(rows.next());
                try (var input = rows.getBinaryStream(1)) {
                    var progress = PurgeProgressCodec.decode(input.readNBytes(PurgeProgressCodec.MAX_BYTES + 1));
                    assertFalse(rows.next());
                    return progress;
                }
            }
        }
    }

    @Test void actualInitialActiveThenPurgeErasesSelectedTenantSourceAndNativeStores() throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = fixture()) {
            var bound = activate(f);
            assertEquals(f.originalSource, f.sourceSnapshot(), "INITIAL must not mutate source rows");
            var facade = f.database.configureDataProtection(List.of(bound.plan()), 1);
            var options = options(f);
            CacheDataProtection.Result result = null;
            CacheDataProtection.Continuation continuation = null;
            try {
                for (int call = 0; call < 1024; call++) {
                    result = facade.purgeTenant(CacheInitialNativeBridgeProvidersRedisTest.Row.class, "a", options, continuation);
                    continuation = result.continuation();
                    assertNotEquals(CacheDataProtection.Status.INCOMPLETE, result.status());
                    assertNotEquals(CacheDataProtection.Status.MIGRATION_REQUIRED, result.status());
                    if (result.status() == CacheDataProtection.Status.COMPLETE) break;
                }
                assertNotNull(result);
                assertEquals(CacheDataProtection.Status.COMPLETE, result.status(), "Bounded purge did not finish");
                assertEquals(Stage.COMPLETE, result.receipt().stage());
                assertEquals(3, result.receipt().sourceRows(), "Includes the cold tenant-a row 99");
                assertEquals(2, result.receipt().redisRows());
                assertEquals(Map.of(3L, f.originalSource.get(3L)), f.sourceSnapshot(), "Foreign tenant source must survive exactly");
                var keys = f.database.redisKeyStrategy();
                for (long id : List.of(1L, 2L, 99L)) {
                    assertFalse(f.redis.exists(keys.entityKey(NS, id)));
                    assertFalse(f.redis.exists(keys.versionKey(NS, id)));
                    assertFalse(f.redis.exists(keys.versionKey(NS, id) + ":tenant"));
                    assertFalse(f.redis.exists(keys.indexMetaKey(NS, id)));
                }
                var generation = f.redis.hgetAll(bound.enrollment().barrier(ENTITY).generationKey(f.generation.key()));
                assertEquals("CLOSED", generation.get("state"));
                assertTrue(generation.keySet().stream().noneMatch(field -> field.startsWith("i:")));
                assertEquals(1, count(f, "cachedb_namespace_bootstrap"), "Birth grant must remain consumed");
                assertEquals(0, count(f, "cachedb_initial_bootstrap"));
                assertEquals(0, count(f, "cachedb_initial_page"));
                assertEquals(1, count(f, "cachedb_tenant_guard"));
                assertEquals(1, count(f, "cachedb_tenant_allocator"));
                try (var c = f.source.getConnection()) {
                    c.setAutoCommit(false);
                    assertThrows(SQLException.class, () -> f.guard.authorizeWrite(c, f.generation));
                    c.rollback();
                }
                assertNotNull(result.evidence());
                f.evidence("PASS hardening=INITIAL_ACTIVE_PURGE actualJdbcSourceRead=true actualJdbcSourceErase=true"
                        + " sourceRowsErased=3 foreignSourceRowsRetained=1 nativeRowsErased=2 birthGrantRetained=true generation=CLOSED");
            } finally {
                if (continuation != null) facade.abandon(continuation);
            }
        }
    }

    @Test void initialCleanupCloseAcceptsTheActualFinalizationEpochUnderARealPurgeLease() throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = fixture()) {
            var bound = activate(f);
            var lease = close(f, bound);
            try {
                var participant = bound.enrollment().initialSourceParticipant(f.metadata, bound.namespace().window());
                var applied = new JdbcPurgeParticipantJournal(f.guard, 2, 1)
                        .step(lease, 1, participant, PurgeParticipant.Phase.CLOSE, 1048576);
                assertEquals(PurgeParticipant.State.VERIFIED, applied.checkpoint().state());
                assertTrue(f.redis.hexists(bound.enrollment().barrier(ENTITY).generationKey(lease.key()), "i:finalize_epoch"));
                assertEquals(f.originalSource, f.sourceSnapshot(), "CLOSE is not source erasure");
                f.evidence("PASS hardening=INITIAL_LEDGER_CLOSE realSqlLease=true actualNativeClosed=true sourceErase=false");
            } finally { release(f, bound, lease); }
        }
    }

    @Test void identityCloseAcceptsRetainedInitialFieldsBeforeExternalEraseUnderARealPurgeLease() throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = fixture()) {
            var bound = activate(f);
            var lease = close(f, bound);
            try {
                var evidence = bound.enrollment().readFence(ENTITY).closeAndDrain(lease, 1,
                        bound.plan().manifestToken(), "", 128, 5000);
                assertEquals(TenantReadFence.State.CLOSED, evidence.state());
                var inventory = new RedisIdentityPurgeParticipant.Inventory() {
                    public boolean sealed(Lease ignored) { return false; }
                    public List<PurgeWorkItem> page(Lease ignored, String after, int limit, int bytes) {
                        throw new AssertionError("CLOSE must not consume an erasure inventory");
                    }
                };
                var identity = new RedisIdentityPurgeParticipant(f.wire, f.database.redisOperations(),
                        f.database.redisKeyStrategy(), bound.enrollment().barrier(ENTITY), NS, "a", ENTITY,
                        f.binding.layoutToken(), Set.of(NS), List.of(bound.enrollment().pointProfileTarget(ENTITY)),
                        inventory, (request, layout) -> {
                            assertEquals(f.binding.layoutToken(), layout);
                            bound.enrollment().readFence(ENTITY).requireClosed(request.lease(), request.generation(),
                                    bound.plan().manifestToken(), evidence);
                            return true;
                        }, Map.of());
                var applied = new JdbcPurgeParticipantJournal(f.guard, 2, 1)
                        .step(lease, 1, identity, PurgeParticipant.Phase.CLOSE, 1048576);
                assertEquals(PurgeParticipant.State.VERIFIED, applied.checkpoint().state());
                var generation = f.redis.hgetAll(bound.enrollment().barrier(ENTITY).generationKey(lease.key()));
                assertTrue(generation.containsKey("i:active"));
                assertTrue(generation.containsKey("i:finalize_epoch"));
                assertEquals(f.originalSource, f.sourceSnapshot(), "Identity CLOSE is not source erasure");
                f.evidence("PASS hardening=IDENTITY_CLOSE realSqlLease=true actualNativeDrain=true initialFieldsRetained=true sourceErase=false");
            } finally { release(f, bound, lease); }
        }
    }

    private record Bound(CacheInitialNamespace<CacheInitialNativeBridgeProvidersRedisTest.Row, Long> namespace,
            CacheTenantRuntimeEnrollment enrollment, RedisTenantPurgePlan plan) {}

    private static CacheInitialNativeBridgeProvidersRedisTest.Fixture fixture() throws Exception {
        return new CacheInitialNativeBridgeProvidersRedisTest.Fixture("postgres");
    }

    private static Bound activate(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) throws Exception {
        var namespace = f.database.prepareInitialNamespace(f.metadata, f.binding, f.guard, f.ids,
                1L, 2L, 2, 65536, 16384, 1048576, 1);
        namespace.bootstrap(new JdbcNamespaceBootstrapGate(f.guard, Set.of(namespace.descriptor().namespaceFingerprint())), f.generation);
        var enrollment = f.database.prepareTenantProtection(1);
        var plan = controlPlan(f, f.database, enrollment, namespace.window());
        var descriptor = enrollment.descriptor(ENTITY);
        var approval = new RedisTenantRuntimeControl.ApprovedRoster(ProtectionKey.hash("hardening-review:" + f.run),
                plan.manifestToken(), descriptor.coverageToken(), List.of(descriptor.runtimeId()), List.of(f.generation.key()));
        var service = namespace.createBootstrap(enrollment, plan, approval, 64, Set.of(namespace.window().fingerprint()));
        var request = new InitialTenantBootstrap.Request(f.generation, "a", UUID.randomUUID(),
                ProtectionKey.hash("hardening-operator:" + f.run), 120);
        for (int call = 0; call < 512; call++) {
            var progress = service.step(request);
            assertNotEquals(Phase.INCOMPLETE, progress.phase());
            if (progress.phase() == Phase.ACTIVE) {
                assertEquals(2, progress.warmedRows());
                assertEquals("OPEN", f.redis.hget(enrollment.barrier(ENTITY).generationKey(f.generation.key()), "state"));
                assertTrue(f.redis.hexists(enrollment.barrier(ENTITY).generationKey(f.generation.key()), "i:finalize_epoch"));
                return new Bound(namespace, enrollment, plan);
            }
        }
        throw new AssertionError("INITIAL did not reach ACTIVE within the fixed continuation budget");
    }

    private static Lease close(CacheInitialNativeBridgeProvidersRedisTest.Fixture f, Bound bound) {
        var lease = f.guard.acquireForTenant(f.binding, "a", options(f), false);
        bound.enrollment().barrier(ENTITY).close(lease, 1);
        var evidence = bound.enrollment().readFence(ENTITY).closeAndDrain(lease, 1, bound.plan().manifestToken(), "", 128, 5000);
        assertEquals(TenantReadFence.State.CLOSED, evidence.state());
        return lease;
    }

    private static void release(CacheInitialNativeBridgeProvidersRedisTest.Fixture f, Bound bound, Lease lease) {
        try { bound.enrollment().readFence(ENTITY).release(lease); }
        finally { try { bound.enrollment().barrier(ENTITY).release(lease); } finally { f.guard.release(lease); } }
    }

    private static PurgeOptions options(CacheInitialNativeBridgeProvidersRedisTest.Fixture f) {
        return new PurgeOptions(UUID.randomUUID(), "a", ProtectionKey.hash("hardening-purge:" + f.run),
                true, 128, 120, 5000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
    }

    private static long count(CacheInitialNativeBridgeProvidersRedisTest.Fixture f, String table) throws SQLException {
        if (!Set.of("cachedb_namespace_bootstrap", "cachedb_initial_bootstrap", "cachedb_initial_page",
                "cachedb_tenant_guard", "cachedb_tenant_allocator").contains(table)) throw new AssertionError("Unknown fixture table");
        try (var c = f.source.getConnection(); var q = c.prepareStatement("SELECT COUNT(*) FROM " + table)) {
            q.setQueryTimeout(2);
            try (var rows = q.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
}
