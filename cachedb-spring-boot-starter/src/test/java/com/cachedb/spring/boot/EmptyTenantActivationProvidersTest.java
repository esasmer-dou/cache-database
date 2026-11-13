package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.EmptyTenantActivation.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.CacheDataProtection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Protocol;

/** Native Redis + three real SQL providers. Uses the existing explicitly controlled route fixture, not a new runtime controller. */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers", matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(300)
class EmptyTenantActivationProvidersTest {
    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void sqlPrepareCrashRetainsCertificateAndNewLeaseRejectsStaleWork(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new CacheDataProtectionProvidersTest.Fixture(provider)) {
            var s = new Session(f);
            assertEquals(Stage.REOPENING, f.guard.load(s.lease).stage());
            assertEquals("1", f.redis.hget(f.barrier.generationKey(s.lease.key()), "generation"));
            sqlRejected(f, 1, "CDBGG"); sqlRejected(f, 2, "CDBGC");
            Request conflict = new Request(UUID.randomUUID(), Mode.EMPTY, f.options(true), s.request.purgeCertificate(), s.request.manifestToken());
            assertEquals(ProtectionException.Code.OPERATION_CONFLICT,
                    assertThrows(ProtectionException.class, () -> s.journal.prepare(s.lease, "a", conflict)).code());
            Request certificate = new Request(s.request.activationId(), Mode.EMPTY, f.options(true), ProtectionKey.hash("wrong-certificate"), s.request.manifestToken());
            assertEquals(ProtectionException.Code.OPERATION_CONFLICT,
                    assertThrows(ProtectionException.class, () -> s.journal.prepare(s.lease, "a", certificate)).code());
            Request manifest = new Request(s.request.activationId(), Mode.EMPTY, f.options(true), s.request.purgeCertificate(), ProtectionKey.hash("wrong-manifest"));
            assertEquals(ProtectionException.Code.OPERATION_CONFLICT,
                    assertThrows(ProtectionException.class, () -> f.facade().reenableEmptyTenant(String.class, "a", manifest)).code());
            expire(f, s.lease);
            var active = recover(f, s.request);
            assertEquals(Phase.ACTIVE, active.checkpoint().phase());
            assertTrue(active.checkpoint().attemptEpoch() > s.lease.epoch());
            assertEquals(ProtectionException.Code.LEASE_LOST,
                    assertThrows(ProtectionException.class, () -> s.journal.queueAcknowledged(s.lease, s.cp)).code());
            assertThrows(RuntimeException.class, () -> f.barrier.prepareEmptyActivation(s.lease, s.cp));
            assertThrows(RuntimeException.class, () -> s.queue.prepareEmptyActivation(s.lease, s.cp));
            assertThrows(RuntimeException.class, () -> f.barrier.installClosedGeneration(s.lease, 2));
            assertActiveAndRejectOldEnvelope(f);
            var replay = f.facade().reenableEmptyTenant(String.class, "a", s.request).checkpoint();
            assertEquals(active.checkpoint().identity(), replay.identity());
            assertEquals(active.checkpoint().receiptToken(), replay.receiptToken());
            assertEquals(active.checkpoint().attemptEpoch(), replay.attemptEpoch());
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void sqlOpenCrashBeforeRedisOpenRecoversTheSameReservation(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new CacheDataProtectionProvidersTest.Fixture(provider)) {
            var s = new Session(f); s.openSql();
            TenantVersionRange frozen = s.cp.reservation();
            assertEquals(Phase.SQL_OPEN, s.cp.phase());
            assertEquals("CLOSED", f.redis.hget(f.barrier.generationKey(s.lease.key()), "state"));
            assertNull(f.redis.hget(f.barrier.generationKey(s.lease.key()), "e:receipt"));
            try (var c = f.source.source.getConnection()) {
                c.setAutoCommit(false); f.guard.authorizeWrite(c, new Generation(s.lease.key(), 2)); c.rollback();
            }
            assertEquals(ProtectionException.Code.RECOVERY_REQUIRED,
                    assertThrows(ProtectionException.class, () -> f.guard.openPreparedGeneration(s.lease)).code());
            assertEquals(ProtectionException.Code.RECOVERY_REQUIRED,
                    assertThrows(ProtectionException.class, () -> s.allocator.reserveOpen(new Generation(s.lease.key(), 2),
                            16, UUID.randomUUID(), frozen.reservationEpoch(), frozen.runId())).code());
            assertThrows(RuntimeException.class, () -> s.nativeVersions.install(frozen));
            expire(f, s.lease);
            var active = recover(f, s.request).checkpoint();
            assertEquals(frozen.request(), active.reservation().request());
            assertEquals(frozen.reservationEpoch(), active.reservation().reservationEpoch());
            assertEquals(frozen.first(), active.reservation().first()); assertEquals(frozen.last(), active.reservation().last());
            assertEquals(ProtectionException.Code.LEASE_LOST,
                    assertThrows(ProtectionException.class, () -> s.journal.complete(s.lease, s.cp,
                            new Receipt(s.cp.identity().token(), s.cp.receiptToken(), frozen.runId(), s.lease.epoch(), s.lease.owner()))).code());
            assertActiveAndRejectOldEnvelope(f);
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void lostRedisOpenOutcomeAndReacquisitionNeverRewindVersionsOrEraseNewWrites(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new CacheDataProtectionProvidersTest.Fixture(provider)) {
            var s = new Session(f); s.openSql();
            s.queue.openEmptyActivation(s.lease, s.cp); s.cp = s.journal.queueAcknowledged(s.lease, s.cp);
            Receipt receipt = f.barrier.activateEmpty(s.lease, s.cp); // Response is deliberately not committed to SQL.
            String owner = f.keys.versionKey(f.ns, "after-activation") + ":tenant";
            String version = f.keys.versionKey(f.ns, "after-activation");
            String entity = f.keys.entityKey(f.ns, "after-activation"); f.redis.set(owner, "a");
            String written = s.nativeVersions.mutate(new Generation(s.lease.key(), 2), owner, "a", version, "-1",
                    List.of(entity), List.of("new-generation-data"), "redis.call('SET',KEYS[6],ARGV[4])");
            f.source.seed("a", 1); // New-generation SQL data must not be re-probed as EMPTY on recovery.
            assertEquals(written, f.redis.get(s.nativeVersions.counterKey(s.lease.key())));
            expire(f, s.lease);
            var active = recover(f, s.request).checkpoint();
            assertEquals(receipt.receiptToken(), active.receiptToken());
            assertEquals(written, f.redis.get(s.nativeVersions.counterKey(s.lease.key())));
            assertEquals("new-generation-data", f.redis.get(entity)); assertEquals(1, f.source.rows("a"));
            assertThrows(RuntimeException.class, () -> f.barrier.activateEmpty(s.lease, s.cp));
            assertEquals(written, f.redis.get(s.nativeVersions.counterKey(s.lease.key())));
            // A normal refill may follow OPEN before an administrator retries the completed activation.
            var state = s.allocator.statusOpen(new Generation(s.lease.key(), 2));
            var refill = s.allocator.reserveOpen(new Generation(s.lease.key(), 2), 16, UUID.randomUUID(),
                    state.reservationEpoch(), active.reservation().runId());
            s.nativeVersions.install(refill);
            String counter = f.redis.get(s.nativeVersions.counterKey(s.lease.key()));
            var replay = f.facade().reenableEmptyTenant(String.class, "a", s.request);
            assertEquals(Phase.ACTIVE, replay.checkpoint().phase());
            assertEquals(counter, f.redis.get(s.nativeVersions.counterKey(s.lease.key())));
            assertEquals(Long.toString(active.attemptEpoch()), f.redis.hget(f.barrier.generationKey(s.lease.key()), "epoch"));
            assertActiveAndRejectOldEnvelope(f);
            f.redis.hset(f.barrier.generationKey(s.lease.key()), "run_id", "0".repeat(40));
            assertThrows(RuntimeException.class, () -> f.facade().reenableEmptyTenant(String.class, "a", s.request));
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void persistAclDenialWritesNothingAndRetryThenReacquisitionPreserveTheReservation(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new CacheDataProtectionProvidersTest.Fixture(provider)) {
            var s = new Session(f); s.openSql();
            s.queue.openEmptyActivation(s.lease, s.cp); s.cp = s.journal.queueAcknowledged(s.lease, s.cp);
            TenantVersionRange frozen = s.cp.reservation();
            List<Object> before = nativeState(s);
            long leaseTtl = f.redis.pttl(f.barrier.leaseKey(s.lease.key()));
            assertTrue(leaseTtl > 0);
            String user = "empty-activation-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
            // The script must not require ACL administration privileges on the calling user.
            f.redis.sendCommand(Protocol.Command.ACL, "SETUSER", user, "reset", "on", ">" + password,
                    "+@all", "-persist", "-acl", "~" + f.prefix + ":*");
            try (var denied = restrictedClient(user, password)) {
                assertThrows(RuntimeException.class, () -> denied.persist(s.nativeVersions.allocatorKey(s.lease.key())));
                var barrier = new RedisGenerationBarrier(JedisPooledOperations.of(denied), f.keys, f.ns);
                RuntimeException failure = assertThrows(RuntimeException.class, () -> barrier.activateEmpty(s.lease, s.cp));
                assertTrue(failure.getMessage().contains("PURGE_RECOVERY_REQUIRED"), failure.toString());
                assertEquals(before, nativeState(s));
                assertTrue(f.redis.pttl(f.barrier.leaseKey(s.lease.key())) <= leaseTtl);
                f.redis.sendCommand(Protocol.Command.ACL, "SETUSER", user, "+persist");
                assertEquals(s.cp.receiptToken(), barrier.activateEmpty(s.lease, s.cp).receiptToken());
            } finally { f.redis.sendCommand(Protocol.Command.ACL, "DELUSER", user); }
            String written = writeVersion(s, "acl-retry");
            expire(f, s.lease);
            var active = recover(f, s.request).checkpoint();
            assertSameReservation(frozen, active.reservation());
            assertTrue(active.attemptEpoch() > s.lease.epoch());
            assertEquals(written, f.redis.get(s.nativeVersions.counterKey(s.lease.key())));
            before = nativeState(s);
            assertThrows(RuntimeException.class, () -> f.barrier.activateEmpty(s.lease, s.cp));
            assertEquals(before, nativeState(s));
            assertActiveAndRejectOldEnvelope(f);
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void unpublishedInstallationResumesAfterReacquisitionButConsumedCounterNeverRewinds(String provider) throws Exception {
        for (String partial : List.of("legacy-header", "pending-counter", "consumed-counter", "closed-receipt")) {
            try (var admin = CacheTenantContext.administrative(); var f = new CacheDataProtectionProvidersTest.Fixture(provider)) {
                var s = new Session(f, partial.equals("pending-counter")); s.openSql();
                TenantVersionRange frozen = s.cp.reservation();
                String guard = f.barrier.generationKey(s.lease.key()), counter = s.nativeVersions.counterKey(s.lease.key());
                String floor = Long.toString(frozen.first() - 1);
                if (!partial.equals("pending-counter")) installPartialHeader(s);
                else {
                    assertTrue(Long.parseLong(f.redis.hget(s.nativeVersions.allocatorKey(s.lease.key()), "ceiling")) < frozen.first() - 1);
                    f.redis.hset(guard, "e:install", s.cp.receiptToken());
                }
                if (!partial.equals("legacy-header")) f.redis.set(counter,
                        partial.equals("consumed-counter") ? Long.toString(frozen.first()) : floor);
                if (partial.equals("closed-receipt")) f.redis.hset(guard, "e:receipt", s.cp.receiptToken());
                if (partial.equals("consumed-counter") || partial.equals("closed-receipt")) {
                    List<Object> before = nativeState(s);
                    assertThrows(RuntimeException.class, () -> f.barrier.activateEmpty(s.lease, s.cp));
                    assertEquals(before, nativeState(s));
                    expire(f, s.lease);
                    assertThrows(RuntimeException.class, () -> recover(f, s.request));
                    assertEquals("CLOSED", f.redis.hget(guard, "state"));
                    assertEquals(before.get(2), f.redis.get(counter));
                } else {
                    expire(f, s.lease);
                    var active = recover(f, s.request).checkpoint();
                    assertSameReservation(frozen, active.reservation());
                    assertEquals(s.cp.receiptToken(), active.receiptToken());
                    assertEquals(floor, f.redis.get(counter));
                    assertEquals("OPEN", f.redis.hget(guard, "state"));
                    List<Object> before = nativeState(s);
                    assertThrows(RuntimeException.class, () -> f.barrier.activateEmpty(s.lease, s.cp));
                    assertEquals(before, nativeState(s));
                    String written = writeVersion(s, partial);
                    f.facade().reenableEmptyTenant(String.class, "a", s.request);
                    assertEquals(written, f.redis.get(counter));
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void missingNativeAclCheckerFailsClosedBeforeAnyWrite(String provider) throws Exception {
        try (var admin = CacheTenantContext.administrative(); var f = new CacheDataProtectionProvidersTest.Fixture(provider)) {
            var s = new Session(f); s.openSql();
            String script;
            try (var in = Objects.requireNonNull(RedisGenerationBarrier.class.getResourceAsStream("/functions/cachedb-empty-activation.lua"))) {
                script = RedisTenantVersionAllocator.library() + new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String unsupported = script.replace("local aclCheck=redis.acl_check_cmd", "local aclCheck=nil");
            assertNotEquals(script, unsupported);
            var id = s.cp.identity(); var range = s.cp.reservation();
            List<String> keys = List.of(f.barrier.generationKey(s.lease.key()), f.barrier.leaseKey(s.lease.key()),
                    s.nativeVersions.allocatorKey(s.lease.key()), s.nativeVersions.counterKey(s.lease.key()));
            List<String> args = List.of("activate", Long.toString(id.fromGeneration()), Long.toString(id.toGeneration()),
                    id.operationId().toString(), s.cp.attemptOwner().toString(), Long.toString(s.cp.attemptEpoch()),
                    id.operationId() + ":" + s.cp.attemptOwner() + ":" + s.cp.attemptEpoch(),
                    Integer.toString(s.lease.seconds() * 1000), id.token(), s.cp.phase().name(), range.runId(),
                    Long.toString(range.reservationEpoch()), Long.toString(range.first()), Long.toString(range.last()),
                    range.request().toString(), id.inventoryToken(), s.cp.receiptToken());
            List<Object> before = nativeState(s);
            RuntimeException failure = assertThrows(RuntimeException.class, () -> f.redis.eval(unsupported, keys, args));
            assertTrue(failure.getMessage().contains("PURGE_RECOVERY_REQUIRED"), failure.toString());
            assertEquals(before, nativeState(s));
            f.redis.del(keys.get(1)); f.redis.hset(keys.get(1), "wrong", "type");
            assertThrows(RuntimeException.class, () -> f.barrier.activateEmpty(s.lease, s.cp));
            assertEquals(before.get(0), f.redis.hgetAll(keys.get(0)));
            assertEquals(before.get(1), f.redis.hgetAll(keys.get(2)));
            assertEquals(before.get(2), f.redis.get(keys.get(3)));
            assertEquals(Map.of("wrong", "type"), f.redis.hgetAll(keys.get(1)));
        }
    }

    private static JedisPooled restrictedClient(String user, String password) {
        var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(2000)
                .blockingSocketTimeoutMillis(2000).user(user).password(password).build();
        var client = new JedisPooled(new HostAndPort(System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port", 16383)), config);
        client.getPool().setMaxTotal(2); client.getPool().setMaxWait(Duration.ofSeconds(2));
        return client;
    }

    private static List<Object> nativeState(Session s) {
        return Arrays.asList(s.f.redis.hgetAll(s.f.barrier.generationKey(s.lease.key())),
                s.f.redis.hgetAll(s.nativeVersions.allocatorKey(s.lease.key())),
                s.f.redis.get(s.nativeVersions.counterKey(s.lease.key())), s.f.redis.get(s.f.barrier.leaseKey(s.lease.key())));
    }

    private static void installPartialHeader(Session s) {
        var range = s.cp.reservation();
        s.f.redis.hset(s.nativeVersions.allocatorKey(s.lease.key()), Map.of("generation", Long.toString(range.generation()),
                "reservation_epoch", Long.toString(range.reservationEpoch()), "floor", Long.toString(range.first() - 1),
                "ceiling", Long.toString(range.last()), "request", range.request().toString(), "run_id", range.runId(),
                "inventory", range.inventory()));
    }

    private static void assertSameReservation(TenantVersionRange expected, TenantVersionRange actual) {
        assertEquals(expected.key(), actual.key()); assertEquals(expected.generation(), actual.generation());
        assertEquals(expected.controlEpoch(), actual.controlEpoch()); assertEquals(expected.reservationEpoch(), actual.reservationEpoch());
        assertEquals(expected.first(), actual.first()); assertEquals(expected.last(), actual.last());
        assertEquals(expected.request(), actual.request()); assertEquals(expected.runId(), actual.runId());
        assertEquals(expected.inventory(), actual.inventory());
    }

    private static String writeVersion(Session s, String id) {
        String owner = s.f.keys.versionKey(s.f.ns, id) + ":tenant", version = s.f.keys.versionKey(s.f.ns, id);
        s.f.redis.set(owner, "a");
        return s.nativeVersions.mutate(new Generation(s.lease.key(), 2), owner, "a", version, "-1",
                List.of(s.f.keys.entityKey(s.f.ns, id)), List.of("retained"), "redis.call('SET',KEYS[6],ARGV[4])");
    }

    private static final class Session {
        final CacheDataProtectionProvidersTest.Fixture f;
        final JdbcTenantVersionAllocator allocator;
        final RedisTenantVersionAllocator nativeVersions;
        final RedisQueueGenerationFence queue;
        final JdbcEmptyTenantActivation journal;
        final Request request;
        final Lease lease;
        Checkpoint cp;
        Session(CacheDataProtectionProvidersTest.Fixture f) throws Exception { this(f, false); }
        Session(CacheDataProtectionProvidersTest.Fixture f, boolean leaveUninstalledReservation) throws Exception {
            this.f = f; f.seed(); var purge = f.finish(true);
            assertEquals(CacheDataProtection.Status.COMPLETE, purge.status());
            request = new Request(UUID.randomUUID(), Mode.EMPTY, f.options(true), purge.evidence(), f.plan.manifestToken());
            allocator = new JdbcTenantVersionAllocator(f.guard, f.source.dialect, 2);
            nativeVersions = new RedisTenantVersionAllocator(f.client, f.keys, f.barrier, f.ns);
            queue = new RedisQueueGenerationFence(f.client, f.stream);
            lease = f.guard.acquireForTenant(f.binding, "a", f.options(true), true);
            f.barrier.close(lease, 1);
            // Establish a coherent old native reservation using the real durable allocator, not invented highwaters.
            var status = allocator.statusClosed(lease, 1);
            var old = allocator.reserveClosed(lease, 1, 16, UUID.randomUUID(), status.reservationEpoch(), nativeVersions.runId(lease.key()));
            nativeVersions.install(old);
            if (leaveUninstalledReservation) allocator.reserveClosed(lease, 1, 16, UUID.randomUUID(), old.reservationEpoch(), old.runId());
            journal = new JdbcEmptyTenantActivation(f.guard, allocator, f.binding, f.plan.manifestToken(), 1);
            cp = journal.prepare(lease, "a", request); // Crash boundary: SQL prepare committed, Redis still g.
        }
        void openSql() {
            String run = f.barrier.prepareEmptyActivation(lease, cp); queue.prepareEmptyActivation(lease, cp);
            cp = journal.queueAcknowledged(lease, cp);
            var evidence = f.fence.closeAndDrain(lease, 2, f.plan.manifestToken(), "", 128, 5000);
            f.fence.requireClosed(lease, 2, f.plan.manifestToken(), evidence); f.guard.validateSource(f.binding);
            cp = journal.openSql(lease, "a", cp, run, 128);
        }
    }
    private static CacheDataProtection.EmptyActivationResult recover(CacheDataProtectionProvidersTest.Fixture f, Request request) {
        CacheDataProtection.Continuation continuation = null;
        for (int step = 0; step < 16; step++) {
            var result = f.facade().reenableEmptyTenant(String.class, "a", request, continuation);
            if (result.checkpoint().phase() == Phase.ACTIVE) return result;
            continuation = result.continuation(); assertNotNull(continuation);
        }
        throw new AssertionError("EMPTY activation exceeded bounded steps");
    }
    private static void expire(CacheDataProtectionProvidersTest.Fixture f, Lease lease) throws Exception {
        try (var c = f.source.source.getConnection(); var q = c.prepareStatement("UPDATE cachedb_tenant_guard SET lease_until=NULL WHERE scope_token=? AND tenant_token=? AND lease_epoch=?")) {
            q.setQueryTimeout(2); q.setString(1, lease.key().scopeToken()); q.setString(2, lease.key().tenantToken()); q.setLong(3, lease.epoch());
            assertEquals(1, q.executeUpdate());
        }
        assertEquals(ProtectionException.Code.LEASE_LOST, assertThrows(ProtectionException.class, () -> f.guard.load(lease)).code());
    }
    private static void sqlRejected(CacheDataProtectionProvidersTest.Fixture f, long generation, String state) throws Exception {
        try (var c = f.source.source.getConnection()) {
            c.setAutoCommit(false);
            assertEquals(state, assertThrows(java.sql.SQLException.class,
                    () -> f.guard.authorizeWrite(c, new Generation(f.binding.key("a"), generation))).getSQLState());
            c.rollback();
        }
    }
    private static void assertActiveAndRejectOldEnvelope(CacheDataProtectionProvidersTest.Fixture f) throws Exception {
        sqlRejected(f, 1, "CDBGG"); assertEquals("2", f.redis.hget(f.barrier.generationKey(f.binding.key("a")), "generation"));
        assertEquals("OPEN", f.redis.hget(f.barrier.generationKey(f.binding.key("a")), "state"));
        assertEquals(1, f.source.rows("b")); assertEquals("foreign", f.redis.get(f.keys.entityKey(f.ns, f.foreign)));
        var operation = new QueuedWriteOperation(OperationType.UPSERT, "PurgeFacadeRow", f.binding.source().table(), f.ns,
                null, "id", "entity_version", null, f.hot, Map.of("id", f.hot, "tenant_id", "a"), 701, Instant.EPOCH, null, null, 0);
        var old = new Generation(f.binding.key("a"), 1);
        var envelope = new QueuedGenerationEnvelope(operation, Map.of(QueuedGenerationEnvelope.identity(operation), old));
        assertThrows(RuntimeException.class, () -> RedisQueueGenerationFence.publish(f.client, f.stream, f.fields("a", f.hot), envelope));
        assertEquals(0, RedisGenerationDurabilityProbe.advanceOne(f.client, f.keys, operation, old));
        assertFalse(f.redis.exists(f.keys.durabilityKey(f.ns, f.hot)));
    }
}
