package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.*;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

/** Real namespace birth, native closure and bounded source-owner preparation; never a purge-completion certificate. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(60)
class CacheTenantRuntimeEnrollmentRedisTest {
    record Row(Long id, String tenant) {}
    private static final String ENTITY = "EnrollmentRow", NS = "enrollment_rows", TENANT = "private-tenant";
    private static final CachePolicy POLICY = CachePolicy.defaults();
    private static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) { return row.id() + "|" + row.tenant(); }
        public Row fromRedisValue(String value) { var parts = value.split("\\|", -1); return new Row(Long.valueOf(parts[0]), parts[1]); }
        public Map<String, Object> toColumns(Row row) { return Map.of("id", row.id(), "tenant", row.tenant()); }
        public Row fromColumns(Map<String, Object> values) { return new Row(((Number)values.get("id")).longValue(), (String)values.get("tenant")); }
    };
    private static final EntityMetadata<Row, Long> META = new EntityMetadata<>() {
        public String entityName() { return ENTITY; }
        public String tableName() { return NS; }
        public String redisNamespace() { return NS; }
        public String idColumn() { return "id"; }
        public Class<Row> entityType() { return Row.class; }
        public Function<Row, Long> idAccessor() { return Row::id; }
        public String tenantFieldName() { return "tenant"; }
        public String extractTenantId(Row row) { return row.tenant(); }
        public boolean tenantPurgeEnabled() { return true; }
        public List<String> columns() { return List.of("id", "tenant"); }
        public Set<String> indexedColumns() { return Set.of("tenant"); }
        public Map<String, String> columnTypes() { return Map.of("id", "java.lang.Long", "tenant", "java.lang.String"); }
        public List<RelationDefinition> relations() { return List.of(); }
    };

    @Test void purgeRejectsNonAdministrativeCallsBeforeOptionsValidationOrIo() {
        assertPurgeRejectionHasNoIo(facade -> {
            var options = purgeAdmissionOptions(TENANT);
            try (var empty = CacheTenantContext.empty()) {
                assertEquals(ProtectionException.Code.ADMIN_REQUIRED, assertThrows(ProtectionException.class,
                        () -> facade.purgeTenant(Row.class, TENANT, options)).code());
                assertEquals(ProtectionException.Code.ADMIN_REQUIRED, assertThrows(ProtectionException.class,
                        () -> facade.purgeTenant(null, null, null, null)).code());
            }
            try (var tenant = CacheTenantContext.withTenant(TENANT)) {
                assertEquals(ProtectionException.Code.ADMIN_REQUIRED, assertThrows(ProtectionException.class,
                        () -> facade.purgeTenant(Row.class, TENANT, options, null)).code());
                assertEquals(ProtectionException.Code.ADMIN_REQUIRED, assertThrows(ProtectionException.class,
                        () -> facade.purgeTenant(null, null, null)).code());
            }
        });
    }

    @Test void purgeRejectsWrongConfirmationBeforePlanLookupOrIo() {
        assertPurgeRejectionHasNoIo(facade -> {
            var options = purgeAdmissionOptions(TENANT.toUpperCase(Locale.ROOT));
            try (var admin = CacheTenantContext.administrative()) {
                assertEquals(ProtectionException.Code.CONFIRMATION_REQUIRED, assertThrows(ProtectionException.class,
                        () -> facade.purgeTenant(Row.class, TENANT, options)).code());
                assertEquals(ProtectionException.Code.CONFIRMATION_REQUIRED, assertThrows(ProtectionException.class,
                        () -> facade.purgeTenant(Row.class, TENANT, options, null)).code());
                assertEquals(ProtectionException.Code.CONFIRMATION_REQUIRED, assertThrows(ProtectionException.class,
                        () -> facade.purgeTenant(null, TENANT, options, null)).code());
            }
        });
    }

    @Test void administrativePurgeNullOptionsAndInvalidTenantRemainDistinctBeforeIo() {
        assertPurgeRejectionHasNoIo(facade -> {
            var options = purgeAdmissionOptions(TENANT);
            try (var admin = CacheTenantContext.administrative()) {
                assertThrows(NullPointerException.class, () -> facade.purgeTenant(Row.class, TENANT, null));
                assertThrows(NullPointerException.class, () -> facade.purgeTenant(null, null, null, null));
                for (String invalidTenant : new String[]{null, "", "tenant\n"}) {
                    assertEquals(ProtectionException.Code.INVALID_REQUEST, assertThrows(ProtectionException.class,
                            () -> facade.purgeTenant(Row.class, invalidTenant, options)).code());
                    assertEquals(ProtectionException.Code.INVALID_REQUEST, assertThrows(ProtectionException.class,
                            () -> facade.purgeTenant(Row.class, invalidTenant, options, null)).code());
                }
            }
        });
    }

    private static PurgeOptions purgeAdmissionOptions(String confirmation) {
        return new PurgeOptions(UUID.randomUUID(), confirmation, ProtectionKey.hash("purge-admission-operator"),
                true, 128, 120, 5000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
    }

    private static void assertPurgeRejectionHasNoIo(java.util.function.Consumer<CacheDataProtection> rejection) {
        try (var f = new Pair(true)) {
            var generationKey = f.a.enrollment.barrier(ENTITY).generationKey(f.lease.key());
            var generation = f.redis.hgetAll(generationKey);
            var roster = f.redis.hgetAll(f.rosterKey());
            long aBorrows = f.a.client.getPool().getBorrowedCount();
            long bBorrows = f.b.client.getPool().getBorrowedCount();
            assertEquals(0, f.source.calls.get(), "Fixture setup must finish before the admission check");
            rejection.accept(f.a.database.dataProtection());
            assertEquals(0, f.source.calls.get(), "Rejected purge must not borrow JDBC or acquire a SQL lease");
            assertEquals(aBorrows, f.a.client.getPool().getBorrowedCount(), "No primary Redis dispatch");
            assertEquals(bBorrows, f.b.client.getPool().getBorrowedCount(), "No peer Redis dispatch");
            assertEquals(generation, f.redis.hgetAll(generationKey));
            assertEquals(roster, f.redis.hgetAll(f.rosterKey()));
        }
    }

    @Test void twoActualRootsDiscoverAndAcknowledgeExistingClosureWithoutSyntheticProbe() {
        try (var f = new Pair(true); var admin = CacheTenantContext.administrative()) {
            assertNotEquals(f.a.enrollment.descriptor(ENTITY).runtimeId(), f.b.enrollment.descriptor(ENTITY).runtimeId());
            var first = f.round();
            assertEquals(TenantReadFence.State.DRAINING, first.state());
            assertEquals(2, first.registered()); assertEquals(1, first.acknowledged());
            var poll = f.b.enrollment.pollControl(1, 5000);
            assertEquals(1, poll.examined()); assertEquals(1, poll.locallyAcknowledged());
            assertEquals(0, poll.incomplete());
            var complete = f.round();
            assertEquals(TenantReadFence.State.CLOSED, complete.state()); assertEquals(2, complete.acknowledged());
            f.a.enrollment.readFence(ENTITY).requireClosed(f.lease, 1, f.a.plan.manifestToken(), complete);
            var retained = f.redis.hgetAll(f.rosterKey());
            var control = f.a.enrollment.controlParticipant(ENTITY);
            assertTrue(control.declaration().mixedOwnership());
            var verified = control.step(new PurgeParticipant.Request(f.lease, 1, PurgeParticipant.Phase.ERASE, "", 1, 1024));
            assertEquals(PurgeParticipant.State.VERIFIED, verified.state()); assertEquals(0, verified.removed());
            assertEquals(retained, f.redis.hgetAll(f.rosterKey()));
            assertEquals(-1, f.redis.pttl(f.rosterKey())); assertEquals(0, f.source.calls.get());
        }
    }

    @Test void livePausedOwnedDeliveryCannotBeAcknowledgedUntilPhysicalConsumerExits() throws Exception {
        try (var f = new Pair(true); var executor = Executors.newSingleThreadExecutor()) {
            f.openRedisControlFixture();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            Future<?> physical = executor.submit(() -> f.b.enrollment.deliver(META, TENANT,
                    repository -> repository.findAll(List.of()), rows -> {
                        assertTrue(rows.isEmpty()); entered.countDown(); await(release);
                    }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try (var admin = CacheTenantContext.administrative()) {
                    f.closeNextEpoch();
                    assertEquals(TenantReadFence.State.DRAINING, f.round().state());
                    var blocked = f.b.enrollment.pollControl(1, 5000);
                    assertEquals(0, blocked.locallyAcknowledged()); assertEquals(1, blocked.pending());
                    assertEquals(TenantReadFence.State.DRAINING, f.round().state());
                    assertFalse(physical.isDone());
                }
            } finally { release.countDown(); }
            var outcome = assertThrows(ExecutionException.class, () -> physical.get(5, TimeUnit.SECONDS));
            var safe = assertInstanceOf(ProtectionException.class, outcome.getCause());
            assertFalse(safe.getMessage().contains(TENANT)); assertNull(safe.getCause());
            try (var admin = CacheTenantContext.administrative()) {
                assertEquals(1, f.b.enrollment.pollControl(1, 5000).locallyAcknowledged());
                assertEquals(TenantReadFence.State.CLOSED, f.round().state());
            }
            assertEquals(0, f.source.calls.get());
        }
    }

    @Test void closedOrMissingRuntimeNeverBecomesAnImplicitAckOrRetiredMember() {
        try (var f = new Pair(true); var admin = CacheTenantContext.administrative()) {
            f.b.database.close();
            for (int i = 0; i < 3; i++) {
                var evidence = f.round();
                assertEquals(TenantReadFence.State.DRAINING, evidence.state());
                assertEquals(2, evidence.registered()); assertEquals(1, evidence.acknowledged());
                assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY, assertThrows(ProtectionException.class,
                        () -> f.a.enrollment.readFence(ENTITY).requireClosed(f.lease, 1, f.a.plan.manifestToken(), evidence)).code());
            }
            assertEquals("1", f.redis.hget(f.rosterKey(), "m:" + f.b.enrollmentId));
            assertEquals(-1, f.redis.pttl(f.rosterKey())); assertEquals(0, f.source.calls.get());
        }
    }

    @Test void capacityOneOwnedDeliveryMayUseTheActualRawRepositoryWithoutDoubleAdmission() {
        try(var f=new Pair(true,1)) {
            f.openRedisControlFixture();var delivered=new AtomicInteger();
            f.b.enrollment.deliver(META,TENANT,repository -> repository.findAll(List.of()),rows -> {
                assertTrue(rows.isEmpty());delivered.incrementAndGet();
                assertTrue(f.b.raw.findAll(List.of()).isEmpty());
            });
            assertEquals(1,delivered.get());assertEquals(0,f.source.calls.get());
            try(var admin=CacheTenantContext.administrative()) {
                f.closeNextEpoch();f.round();assertEquals(1,f.b.enrollment.pollControl(1,5000).locallyAcknowledged());
                assertEquals(TenantReadFence.State.CLOSED,f.round().state());
            }
        }
    }

    @Test void phaseAlignedOwnedDeliveriesAtCapacityRetainTheirPhysicalPermitsUntilConsumersExit() throws Exception {
        int capacity=4;
        try(var f=new Pair(true,capacity);var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            f.openRedisControlFixture();var readsReady=new CountDownLatch(capacity);var readNow=new CountDownLatch(1);
            var consumersReady=new CountDownLatch(capacity);var deliverNow=new CountDownLatch(1);
            var tasks=new ArrayList<Future<?>>();
            try {
                for(int i=0;i<capacity;i++)tasks.add(workers.submit(() -> f.b.enrollment.deliver(META,TENANT,repository -> {
                    readsReady.countDown();await(readNow);return repository.findAll(List.of());
                },rows -> {assertTrue(rows.isEmpty());consumersReady.countDown();await(deliverNow);} )));
                assertTrue(readsReady.await(5,TimeUnit.SECONDS));readNow.countDown();
                assertTrue(consumersReady.await(5,TimeUnit.SECONDS));
                try(var admin=CacheTenantContext.administrative()) {
                    f.closeNextEpoch();assertEquals(TenantReadFence.State.DRAINING,f.round().state());
                    assertEquals(1,f.b.enrollment.pollControl(1,5000).pending());
                    assertTrue(tasks.stream().noneMatch(Future::isDone));
                }
            }finally{readNow.countDown();deliverNow.countDown();}
            for(var task:tasks) {
                var safe=assertInstanceOf(ProtectionException.class,
                        assertThrows(ExecutionException.class,() -> task.get(5,TimeUnit.SECONDS)).getCause());
                assertNull(safe.getCause());assertFalse(safe.toString().contains(TENANT));
            }
            try(var admin=CacheTenantContext.administrative()) {
                assertEquals(1,f.b.enrollment.pollControl(1,5000).locallyAcknowledged());
                assertEquals(TenantReadFence.State.CLOSED,f.round().state());
            }
            assertEquals(0,f.source.calls.get());
        }
    }

    @Test void staleAckFailsClosedButItsMemberIsNotRemovedAndCanRefresh() {
        try (var f = new Pair(true); var admin = CacheTenantContext.administrative()) {
            f.round(); f.b.enrollment.pollControl(1, 5000); var old = f.round();
            assertEquals(TenantReadFence.State.CLOSED, old.state());
            f.redis.hset(f.journalKey(), "t:" + f.b.enrollmentId, "0");
            var stale = f.round();
            assertEquals(TenantReadFence.State.DRAINING, stale.state()); assertEquals(2, stale.registered());
            assertThrows(ProtectionException.class,
                    () -> f.a.enrollment.readFence(ENTITY).requireClosed(f.lease, 1, f.a.plan.manifestToken(), old));
            assertEquals("1", f.redis.hget(f.rosterKey(), "m:" + f.b.enrollmentId));
            f.b.enrollment.pollControl(1, 5000);
            assertEquals(TenantReadFence.State.CLOSED, f.round().state());
        }
    }

    @Test void sealingWhileOpenIsRejectedBeforeRosterPublication() {
        try (var f = new Pair(false); var admin = CacheTenantContext.administrative()) {
            f.openRedisControlFixture();
            assertThrows(ProtectionException.class, () -> f.a.seal(f.approval(), f.lease));
            assertEquals("COLLECTING", f.redis.hget(f.rosterKey(), "state"));
            assertNull(f.redis.hget(f.rosterKey(), "m:" + f.a.enrollmentId));
            f.closeNextEpoch(); f.sealBoth();
            assertTrue(f.redis.exists(f.rosterKey()));
        }
    }

    @Test void approvalCannotOmitLocalRuntimeChangeMembersOrAdoptAnUnknownSchema() {
        try (var f = new Pair(true); var admin = CacheTenantContext.administrative()) {
            var before = f.redis.hgetAll(f.rosterKey()); var approval = f.approval();
            var changed = new ApprovedRoster(approval.approvalToken(), approval.manifestToken(), approval.coverageToken(),
                    List.of(f.a.enrollmentId, UUID.randomUUID()), approval.pairs());
            assertThrows(ProtectionException.class, () -> f.a.seal(changed, f.lease));
            assertEquals(before, f.redis.hgetAll(f.rosterKey()));
            assertThrows(ProtectionException.class, () -> f.b.seal(changed, f.lease));
            f.redis.hset(f.rosterKey(), "unknown-owner", "not-owned");
            assertThrows(ProtectionException.class, f::round);
            assertEquals("not-owned", f.redis.hget(f.rosterKey(), "unknown-owner"));
        }
    }

    @Test void fixedCatalogueBudgetsAdminAndUnsealedAdmissionRemainExplicit() {
        try (var f = new Pair(false)) {
            assertEquals(ProtectionException.Code.ADMIN_REQUIRED, assertThrows(ProtectionException.class,
                    () -> f.a.enrollment.pollControl(1, 1000)).code());
            assertThrows(ProtectionException.class, () -> f.a.enrollment.deliver(META, TENANT,
                    repository -> repository.findAll(List.of()), ignored -> fail("Unsealed delivery")));
            try (var admin = CacheTenantContext.administrative()) {
                assertEquals(1, f.a.enrollment.pollControl(1, 1000).incomplete());
                assertThrows(ProtectionException.class, () -> f.a.enrollment.pollControl(65, 1000));
                assertThrows(ProtectionException.class, () -> f.a.enrollment.pollControl(1, 5001));
                assertThrows(ProtectionException.class, () -> new ApprovedRoster(ProtectionKey.hash("a"),
                        f.a.plan.manifestToken(), f.a.enrollment.descriptor(ENTITY).coverageToken(), List.of(), List.of(f.lease.key())));
            }
        }
    }

    @Test void actualRestartIncarnationCannotJoinOrReplaceSealedApproval() {
        try (var f = new Pair(true); var restarted = new Root(f); var admin = CacheTenantContext.administrative()) {
            assertNotEquals(f.a.enrollmentId, restarted.enrollmentId);
            var before = f.redis.hgetAll(f.rosterKey());
            assertThrows(ProtectionException.class, () -> restarted.seal(f.approval(), f.lease));
            assertEquals(before, f.redis.hgetAll(f.rosterKey()));
            assertNull(f.redis.hget(f.rosterKey(), "m:" + restarted.enrollmentId));
            assertEquals("1", f.redis.hget(f.rosterKey(), "e:" + restarted.enrollmentId));
            assertEquals("REVIEW_REQUIRED", f.redis.hget(f.rosterKey(), "state"));
        }
    }

    @Test void exactEnrolledSetCannotBeReplacedByCallerApprovedSubset() {
        try (var f = new Pair(false); var third = new Root(f, false); var admin = CacheTenantContext.administrative()) {
            assertEquals("3", f.redis.hget(f.rosterKey(), "enrolled"));
            var before = f.redis.hgetAll(f.rosterKey());
            assertThrows(ProtectionException.class, () -> f.a.seal(f.approval(), f.lease));
            assertEquals(before, f.redis.hgetAll(f.rosterKey()));
            assertEquals("COLLECTING", f.redis.hget(f.rosterKey(), "state"));
            assertEquals(0, f.source.calls.get());
        }
    }

    @Test void invalidPublicProducerCapacityCannotEnrollOrInvalidateAnExistingSeal() {
        try (var f = new Pair(true); var client = f.client();
                var invalid = new CacheDatabase(client, f.source, f.a.database.config())) {
            invalid.start(); invalid.register(META, CODEC, POLICY);
            var before = f.redis.hgetAll(f.rosterKey());
            for (int capacity : List.of(0, -1, 4097)) {
                var rejected = assertThrows(ProtectionException.class, () -> invalid.configureTenantProducer(META, capacity));
                assertEquals(ProtectionException.Code.INVALID_REQUEST, rejected.code()); assertNull(rejected.getCause());
                assertEquals(before, f.redis.hgetAll(f.rosterKey()));
            }
            assertEquals("SEALED", f.redis.hget(f.rosterKey(), "state"));
            assertEquals("2", f.redis.hget(f.rosterKey(), "enrolled")); assertEquals(0, f.source.calls.get());
        }
    }

    @Test void thirdOrdinaryConfiguredRootWithoutPrepareCannotReadWriteOrLeaveTwoOfTwoClosureValid() {
        try (var f = new Pair(true)) {
            f.openRedisControlFixture();
            try (var tenant = CacheTenantContext.withTenant(TENANT)) {
                assertTrue(f.a.raw.findAll(List.of()).isEmpty()); assertTrue(f.b.raw.findAll(List.of()).isEmpty());
            }
            var original = f.a.enrollment.barrier(ENTITY).captureOpen(f.lease.key());
            // Uses the PUBLIC configure + raw repository paths, never prepare/deliver.
            try (var third = new Root(f, false); var tenant = CacheTenantContext.withTenant(TENANT)) {
                assertEquals("REVIEW_REQUIRED", f.redis.hget(f.rosterKey(), "state"));
                assertEquals("3", f.redis.hget(f.rosterKey(), "enrolled"));
                assertEquals("1", f.redis.hget(f.rosterKey(), "e:" + third.enrollmentId));
                assertNull(f.redis.hget(f.rosterKey(), "m:" + third.enrollmentId));
                for (var root : List.of(f.a, f.b, third)) {
                    assertThrows(RuntimeException.class, () -> root.raw.findAll(List.of()));
                    assertThrows(RuntimeException.class, () -> root.raw.findById(99L));
                    assertThrows(RuntimeException.class, () -> root.raw.saveWithReceipt(new Row(99L, TENANT)));
                }
                String sideEffect = f.prefix + ":must-not-publish";
                var failure = assertThrows(RuntimeException.class, () -> f.a.enrollment.barrier(ENTITY).evalOpen(original,
                        sideEffect + ":owner", "uninstalled", "return redis.call('SET',KEYS[3],'unsafe')",
                        List.of(sideEffect), List.of()));
                assertTrue(failure.getMessage().contains("CDB_RUNTIME_NOT_APPROVED"));
                assertFalse(f.redis.exists(sideEffect));
                assertFalse(f.redis.exists(f.a.database.redisKeyStrategy().versionKey(NS, 99L)));
            }
            // Closing the third root cannot retire its durable membership or restore an old seal.
            try (var admin = CacheTenantContext.administrative()) {
                f.closeNextEpoch();
                assertThrows(ProtectionException.class, f::round);
                assertEquals("3", f.redis.hget(f.rosterKey(), "enrolled"));
                assertEquals("1", f.redis.hget(f.rosterKey(), "m:" + f.a.enrollmentId));
                assertEquals("1", f.redis.hget(f.rosterKey(), "m:" + f.b.enrollmentId));
                assertEquals(-1, f.redis.pttl(f.rosterKey()));
            }
            assertEquals(0, f.source.calls.get());
        }
    }

    @Test void expiringRosterIsRejectedRatherThanRefreshedOrTreatedAsRetirement() {
        try (var f = new Pair(true); var admin = CacheTenantContext.administrative()) {
            f.redis.pexpire(f.rosterKey(), 30000);
            assertThrows(ProtectionException.class, f::round);
            assertEquals("1", f.redis.hget(f.rosterKey(), "m:" + f.b.enrollmentId));
            assertTrue(f.redis.pttl(f.rosterKey()) > 0); assertTrue(f.redis.pttl(f.rosterKey()) <= 30000);
        }
    }

    @Test void replacementLeaseCannotReusePriorOwnersAckCertificate() {
        try (var f = new Pair(true); var admin = CacheTenantContext.administrative()) {
            f.round(); f.b.enrollment.pollControl(1, 5000); var old = f.round();
            assertEquals(TenantReadFence.State.CLOSED, old.state());
            f.closeNextEpoch();
            assertEquals(TenantReadFence.State.DRAINING, f.round().state());
            assertThrows(ProtectionException.class,
                    () -> f.a.enrollment.readFence(ENTITY).requireClosed(f.lease, 1, f.a.plan.manifestToken(), old));
            f.b.enrollment.pollControl(1, 5000); var fresh = f.round();
            assertEquals(TenantReadFence.State.CLOSED, fresh.state()); assertNotEquals(old.token(), fresh.token());
            assertThrows(ProtectionException.class,
                    () -> f.a.enrollment.readFence(ENTITY).requireClosed(f.lease, 1, f.a.plan.manifestToken(), old));
        }
    }

    @Test void boundedCatalogueFindsSecondPairRoundRobinWithoutDiscoveringForeignTenants() {
        try (var f = new Pair(false); var admin = CacheTenantContext.administrative()) {
            var other = new Lease(f.a.enrollment.barrier(ENTITY).key("another-private-tenant"),
                    UUID.randomUUID(), UUID.randomUUID(), 1, 120);
            f.a.enrollment.barrier(ENTITY).close(other, 1);
            var original = f.approval();
            var approval = new ApprovedRoster(original.approvalToken(), original.manifestToken(), original.coverageToken(),
                    original.members(), List.of(f.lease.key(), other.key()));
            var closed = List.of(new ClosedPair(f.lease, 1), new ClosedPair(other, 1));
            f.a.enrollment.sealClosed(f.a.plan, approval, closed); f.b.enrollment.sealClosed(f.b.plan, approval, closed);
            var fence = f.a.enrollment.readFence(ENTITY);
            assertEquals(TenantReadFence.State.DRAINING,
                    fence.closeAndDrain(other, 1, f.a.plan.manifestToken(), "", 128, 5000).state());
            var first = f.b.enrollment.pollControl(1, 5000); var second = f.b.enrollment.pollControl(1, 5000);
            assertEquals(1, first.examined()); assertEquals(1, second.examined());
            assertEquals(1, first.locallyAcknowledged() + second.locallyAcknowledged());
            assertEquals(TenantReadFence.State.CLOSED,
                    fence.closeAndDrain(other, 1, f.a.plan.manifestToken(), "", 128, 5000).state());
            var foreign = new Lease(f.a.enrollment.barrier(ENTITY).key("not-in-catalogue"),
                    UUID.randomUUID(), UUID.randomUUID(), 1, 120);
            assertThrows(ProtectionException.class,
                    () -> fence.closeAndDrain(foreign, 1, f.a.plan.manifestToken(), "", 128, 5000));
            assertEquals(0, f.source.calls.get());
        }
    }

    @Test void sourceOwnerFactoryBindsActualRootAndReviewedWorklistWithoutSqlOrOwnerWrites() {
        try (var admin=CacheTenantContext.administrative(); var f=new Pair(true)) {
            var ids=JdbcSourceOwnerOnboarding.longIds();
            var plan=new SourceOwnerOnboarding.Plan(f.a.plan.binding,META.versionColumn(),ids.formatToken());
            var prepared=f.a.database.prepareSourceOwnerOnboarding(META,f.a.enrollment,f.a.plan,f.approval(),
                    ids,List.of(1L,2L),Set.of(plan.fingerprint()));
            assertEquals(plan,prepared.plan()); assertTrue(prepared.pendingClosure().isEmpty());
            var configured=new JdbcSourceOwnerOnboarding.JdbcIdCodec<Long>() {
                public String formatToken() { return ids.formatToken(); }
                public String encode(Long id) { throw new AssertionError("Configured encode must not supply authority"); }
                public Long decode(String value) { throw new AssertionError("Configured decode must not supply authority"); }
                public Set<Integer> jdbcTypes() { throw new AssertionError("Configured JDBC types must not supply authority"); }
                public void bind(java.sql.PreparedStatement statement,int parameter,Long id,int type) {
                    throw new AssertionError("Configured JDBC binding must not supply authority");
                }
                public Long read(java.sql.ResultSet rows,int column) {
                    throw new AssertionError("Configured JDBC decoding must not supply authority");
                }
            };
            var nativeOnly=f.a.database.prepareSourceOwnerOnboarding(META,f.a.enrollment,f.a.plan,f.approval(),
                    configured,List.of(1L,2L),Set.of(plan.fingerprint()));
            assertEquals(prepared.worklistToken(),nativeOnly.worklistToken());
            var changed=f.a.database.prepareSourceOwnerOnboarding(META,f.a.enrollment,f.a.plan,f.approval(),
                    ids,List.of(1L,3L),Set.of(plan.fingerprint()));
            assertNotEquals(prepared.worklistToken(),changed.worklistToken());
            assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,assertThrows(ProtectionException.class,
                    ()->f.a.database.prepareSourceOwnerOnboarding(META,f.b.enrollment,f.a.plan,f.approval(),
                            ids,List.of(1L),Set.of(plan.fingerprint()))).code());
            assertEquals(ProtectionException.Code.UNWIRED_PROTOCOL,assertThrows(ProtectionException.class,
                    ()->f.a.database.prepareSourceOwnerOnboarding(META,f.a.enrollment,f.a.plan,f.approval(),
                            ids,List.of(1L),Set.of())).code());
            assertEquals(0,f.source.calls.get());
            assertNull(f.redis.get(f.a.database.redisKeyStrategy().versionKey(NS,1L)+":tenant"));
        }
    }

    @Test void sourceOwnerInstallIsIdempotentWithoutChangingPayloadVersionsTombstonesOrTtl() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L,2L))) {
            var keys=f.pair.a.database.redisKeyStrategy();
            String entity=keys.entityKey(NS,1L), version=keys.versionKey(NS,1L), tomb=keys.tombstoneKey(NS,1L);
            f.pair.redis.setex(entity,120,"existing-payload"); f.pair.redis.set(version,"7"); f.pair.redis.set(tomb,"6");
            long ttl=f.pair.redis.pttl(entity);
            var installed=f.install(f.proofs(7,7));
            assertEquals(new SourceOwnerOnboarding.Installed(1,1,0),installed);
            assertEquals(installed,f.install(f.proofs(7,7))); assertTrue(f.verify());
            assertEquals("existing-payload",f.pair.redis.get(entity)); assertEquals("7",f.pair.redis.get(version));
            assertEquals("6",f.pair.redis.get(tomb)); assertTrue(f.pair.redis.pttl(entity)>0 && f.pair.redis.pttl(entity)<=ttl);
            assertEquals(TENANT,f.pair.redis.get(version+":tenant")); assertEquals(-1,f.pair.redis.pttl(version+":tenant"));
            assertNull(f.pair.redis.get(keys.entityKey(NS,2L))); assertNull(f.pair.redis.get(keys.versionKey(NS,2L)+":tenant"));
            assertEquals("CLOSED",f.pair.redis.hget(f.pair.a.plan.barrier.generationKey(f.session.lease().key()),"state"));
            assertEquals(0,f.pair.source.calls.get());
        }
    }

    @Test void sourceOwnerForeignLaterIdentityPreventsEveryEarlierOwnerWrite() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L,2L))) {
            var keys=f.pair.a.database.redisKeyStrategy();
            f.pair.redis.set(keys.entityKey(NS,1L),"one"); f.pair.redis.set(keys.entityKey(NS,2L),"two");
            f.pair.redis.set(keys.versionKey(NS,2L)+":tenant","another-tenant");
            assertThrows(ProtectionException.class,()->f.install(f.proofs(7,7)));
            assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
            assertEquals("another-tenant",f.pair.redis.get(keys.versionKey(NS,2L)+":tenant"));
        }
    }

    @Test void sourceOwnerUnknownSourceNeverClaimsExistingOrMissingIdentity() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L,2L))) {
            var keys=f.pair.a.database.redisKeyStrategy(); f.pair.redis.set(keys.entityKey(NS,1L),"one");
            var proofs=List.of(new SourceOwnerOnboarding.OwnerProof<>(1L,"1",SourceOwnerOnboarding.Ownership.UNKNOWN,0),
                    new SourceOwnerOnboarding.OwnerProof<>(2L,"2",SourceOwnerOnboarding.Ownership.UNKNOWN,0));
            assertEquals(new SourceOwnerOnboarding.Installed(0,0,2),f.install(proofs));
            assertFalse(f.verify()); assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
            assertNull(f.pair.redis.get(keys.versionKey(NS,2L)+":tenant"));
        }
    }

    @Test void sourceOwnerRetainedQueueAndCompactionHistoryCannotBeStampedOrDeleted() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L))) {
            var keys=f.pair.a.database.redisKeyStrategy(); f.pair.redis.set(keys.entityKey(NS,1L),"one");
            String stream=f.pair.a.plan.queues.getFirst().stream();
            f.pair.redis.eval("return redis.call('XADD',KEYS[1],'*','f','v')",List.of(stream),List.of());
            assertThrows(ProtectionException.class,()->f.install(f.proofs(7)));
            assertEquals(1,f.pair.redis.xlen(stream)); assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
        }
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L))) {
            var keys=f.pair.a.database.redisKeyStrategy(); f.pair.redis.set(keys.entityKey(NS,1L),"one");
            String pending=keys.compactionPendingKey(NS,1L); f.pair.redis.set(pending,"7");
            assertThrows(ProtectionException.class,()->f.install(f.proofs(7)));
            assertEquals("7",f.pair.redis.get(pending)); assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
        }
    }

    @Test void sourceOwnerFutureOrMalformedVersionAndWrongTypeFailBeforeOwnerCreation() {
        for (String value:List.of("8","9007199254740992","01","not-a-version")) {
            try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L))) {
                var keys=f.pair.a.database.redisKeyStrategy();
                f.pair.redis.set(keys.entityKey(NS,1L),"one"); f.pair.redis.set(keys.versionKey(NS,1L),value);
                assertThrows(ProtectionException.class,()->f.install(f.proofs(7)));
                assertEquals(value,f.pair.redis.get(keys.versionKey(NS,1L))); assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
            }
        }
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L))) {
            var keys=f.pair.a.database.redisKeyStrategy(); f.pair.redis.hset(keys.entityKey(NS,1L),"wrong","type");
            assertThrows(ProtectionException.class,()->f.install(f.proofs(7)));
            assertEquals("type",f.pair.redis.hget(keys.entityKey(NS,1L),"wrong"));
            assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
        }
    }

    @Test void sourceOwnerReceiptCannotRepairLostOwnerOrHideChangedSourceProof() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L))) {
            var keys=f.pair.a.database.redisKeyStrategy(); f.pair.redis.set(keys.entityKey(NS,1L),"one");
            f.install(f.proofs(7)); assertTrue(f.verify());
            assertThrows(ProtectionException.class,()->f.install(f.proofs(8)));
            f.pair.redis.del(keys.versionKey(NS,1L)+":tenant");
            assertThrows(ProtectionException.class,f::verify);
            assertThrows(ProtectionException.class,()->f.install(f.proofs(7)));
            assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
        }
    }

    @Test void sourceOwnerMutationAtomicallyRejectsStalePeerAcknowledgementAndOldLease() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L))) {
            var keys=f.pair.a.database.redisKeyStrategy(); f.pair.redis.set(keys.entityKey(NS,1L),"one");
            f.seal(); f.pair.redis.hset(f.pair.journalKey(),"t:"+f.pair.b.enrollmentId,"0");
            assertThrows(ProtectionException.class,()->f.installer.install(f.session,f.page,f.proofs(7)));
            assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
            f.refresh();
            var next=new Lease(f.session.lease().key(),f.session.lease().operationId(),UUID.randomUUID(),
                    f.session.lease().epoch()+1,120);
            f.pair.a.plan.barrier.close(next,1);
            assertThrows(ProtectionException.class,()->f.installer.install(f.session,f.page,f.proofs(7)));
            assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
        }
    }

    @Test void sourceOwnerActualRootWarmsExistingSqlOwnedWorklistAndCannotRebindTerminalCheckpoint() throws Exception {
        try (var admin=CacheTenantContext.administrative(); var f=new Pair(false,8,false);
                var sql=f.source.administrativeSql()) {
            seedOwnerSource(f,1L,TENANT); seedOwnerSource(f,2L,TENANT);
            var keys=f.a.database.redisKeyStrategy();
            f.redis.setex(keys.entityKey(NS,1L),120,"legacy-one"); f.redis.set(keys.versionKey(NS,1L),"4");
            var prepared=ownerAdapter(f,List.of(1L,2L));
            var request=new SourceOwnerOnboarding.Request(prepared.plan(),new Generation(f.lease.key(),1),TENANT,
                    UUID.randomUUID(),ProtectionKey.hash("real-source-owner-operation"),30);
            var result=ownerStepWithActualPeer(f,prepared,request);
            assertEquals(SourceOwnerOnboarding.State.WARMED,result.state());
            assertEquals(2,result.examined()); assertEquals(1,result.owned()); assertEquals(1,result.absent());
            assertEquals(0,result.unknown()); assertEquals(TENANT,f.redis.get(keys.versionKey(NS,1L)+":tenant"));
            assertNull(f.redis.get(keys.versionKey(NS,2L)+":tenant"));
            assertEquals("legacy-one",f.redis.get(keys.entityKey(NS,1L))); assertEquals("4",f.redis.get(keys.versionKey(NS,1L)));
            assertEquals(result,prepared.step(request));
            var changed=ownerAdapter(f,List.of(1L,3L));
            assertEquals(ProtectionException.Code.INVALID_PROGRESS,assertThrows(ProtectionException.class,
                    ()->changed.step(request)).code());
            assertEquals(result,prepared.step(request));
            assertNull(f.redis.get(keys.versionKey(NS,3L)+":tenant"));
            assertOwnerSqlStillClosed(f);
        }
    }

    @Test void sourceOwnerActualSqlForeignIdentityEndsIncompleteWithoutClaimingItOrReopening() throws Exception {
        try (var admin=CacheTenantContext.administrative(); var f=new Pair(false,8,false);
                var sql=f.source.administrativeSql()) {
            seedOwnerSource(f,1L,"another-tenant");
            var keys=f.a.database.redisKeyStrategy(); f.redis.set(keys.entityKey(NS,1L),"legacy-one");
            var prepared=ownerAdapter(f,List.of(1L));
            var request=new SourceOwnerOnboarding.Request(prepared.plan(),new Generation(f.lease.key(),1),TENANT,
                    UUID.randomUUID(),ProtectionKey.hash("real-source-owner-unknown"),30);
            var result=ownerStepWithActualPeer(f,prepared,request);
            assertEquals(SourceOwnerOnboarding.State.INCOMPLETE,result.state()); assertEquals(1,result.unknown());
            assertNull(f.redis.get(keys.versionKey(NS,1L)+":tenant"));
            assertEquals("legacy-one",f.redis.get(keys.entityKey(NS,1L))); assertOwnerSqlStillClosed(f);
        }
    }

    @Test void sourceOwnerRequiresNativeSealAndWillNotRecreateADeletedPageSeal() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L))) {
            var keys=f.pair.a.database.redisKeyStrategy(); f.pair.redis.set(keys.entityKey(NS,1L),"one");
            f.refresh();
            assertThrows(ProtectionException.class,()->f.installer.install(f.session,f.page,f.proofs(7)));
            assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
            String token=f.seal();
            assertEquals(token,f.installer.inventoryToken(f.session.request()));
            String generation=f.pair.a.plan.barrier.generationKey(f.session.lease().key());
            String field="onboard:"+f.session.lease().operationId()+":"+f.page.id()+":snapshot";
            String original=f.pair.redis.hget(generation,field); assertNotNull(original);
            f.pair.redis.hdel(generation,field);
            assertThrows(ProtectionException.class,()->f.install(f.proofs(7)));
            assertThrows(ProtectionException.class,f::seal);
            assertNull(f.pair.redis.hget(generation,field));
            assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
        }
    }

    @Test void sourceOwnerSealedPayloadVersionTombstoneMetadataAndExistenceCannotChange() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L,2L))) {
            var keys=f.pair.a.database.redisKeyStrategy();
            String entity=keys.entityKey(NS,1L), version=keys.versionKey(NS,1L), tomb=keys.tombstoneKey(NS,1L);
            String meta=keys.indexMetaKey(NS,1L), durability=keys.durabilityKey(NS,1L);
            f.pair.redis.set(entity,"one"); f.pair.redis.set(version,"4"); f.pair.redis.set(tomb,"3");
            f.pair.redis.hset(meta,"tenant",TENANT); f.pair.redis.set(durability,"durable");
            String token=f.seal();
            // Same length/type and the same version cannot hide a changed payload.
            f.pair.redis.set(entity,"two"); assertOwnerInstallRejected(f); f.pair.redis.set(entity,"one");
            f.pair.redis.set(version,"5"); assertOwnerInstallRejected(f); f.pair.redis.set(version,"4");
            f.pair.redis.set(tomb,"2"); assertOwnerInstallRejected(f); f.pair.redis.set(tomb,"3");
            f.pair.redis.hset(meta,"tenant","other"); assertOwnerInstallRejected(f); f.pair.redis.hset(meta,"tenant",TENANT);
            f.pair.redis.set(durability,"changed"); assertOwnerInstallRejected(f); f.pair.redis.set(durability,"durable");
            f.pair.redis.del(entity); assertOwnerInstallRejected(f); f.pair.redis.set(entity,"one");
            f.pair.redis.set(keys.entityKey(NS,2L),"new-after-seal"); assertOwnerInstallRejected(f);
            f.pair.redis.del(keys.entityKey(NS,2L));
            f.pair.redis.pexpire(entity,120000); assertOwnerInstallRejected(f); f.pair.redis.persist(entity);
            assertEquals(token,f.seal());
            assertEquals(new SourceOwnerOnboarding.Installed(1,1,0),f.install(f.proofs(7,7)));
            assertTrue(f.verify());
        }
    }

    @Test void sourceOwnerSealRetryReconstructsFromNativeStateAndRejectsSamePageIdDifferentWorklist() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L,2L))) {
            var keys=f.pair.a.database.redisKeyStrategy(); f.pair.redis.set(keys.entityKey(NS,1L),"one");
            String token=f.seal(); var reconstructed=f.installer(f.pages);
            assertEquals(token,reconstructed.seal(f.session));
            var original=f.install(f.proofs(7,7));
            f.refresh(); assertEquals(original,reconstructed.install(f.session,f.page,f.proofs(7,7)));
            assertEquals(token,reconstructed.seal(f.session)); assertTrue(reconstructed.verify(f.session,f.page));
            var changed=new SourceOwnerOnboarding.Page(f.page.id(),"-",true,List.of("1","3"));
            var conflicting=f.installer(List.of(changed));
            assertThrows(ProtectionException.class,()->conflicting.seal(f.session));
            String generation=f.pair.a.plan.barrier.generationKey(f.session.lease().key());
            String rootField="onboard:"+f.session.lease().operationId()+":seal";
            String retained=f.pair.redis.hget(generation,rootField);
            assertEquals(ProtectionException.Code.INVALID_PROGRESS,assertThrows(ProtectionException.class,
                    ()->conflicting.inventoryToken(f.session.request())).code());
            assertEquals(retained,f.pair.redis.hget(generation,rootField));
            assertEquals(token,reconstructed.inventoryToken(f.session.request()));
            String[] partial=retained.split(":"); partial[2]="0";
            String[] stale=retained.split(":");
            stale[4]=(stale[4].charAt(0)=='0'?"1":"0")+stale[4].substring(1);
            for (String invalid:List.of("malformed",String.join(":",partial),String.join(":",stale))) {
                f.pair.redis.hset(generation,rootField,invalid);
                assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY,assertThrows(ProtectionException.class,
                        ()->reconstructed.inventoryToken(f.session.request())).code());
                assertEquals(invalid,f.pair.redis.hget(generation,rootField));
            }
            f.pair.redis.hdel(generation,rootField);
            assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY,assertThrows(ProtectionException.class,
                    ()->reconstructed.inventoryToken(f.session.request())).code());
            assertNull(f.pair.redis.hget(generation,rootField));
            f.pair.redis.hset(generation,rootField,retained);
            assertEquals(token,reconstructed.inventoryToken(f.session.request()));
            assertNull(f.pair.redis.get(keys.versionKey(NS,3L)+":tenant"));
        }
    }

    @Test void sourceOwnerPartialFencingRetainsItsFirstSnapshotAndDoesNotAdvanceOrOverwriteOnChange() {
        var candidates=java.util.stream.LongStream.rangeClosed(1,129).boxed().toList();
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(candidates)) {
            var keys=f.pair.a.database.redisKeyStrategy(); f.pair.redis.set(keys.entityKey(NS,1L),"one");
            String last=keys.entityKey(NS,129L); f.pair.redis.set(last,"x".repeat(1_048_577));
            assertThrows(ProtectionException.class,f::seal);
            String generation=f.pair.a.plan.barrier.generationKey(f.session.lease().key());
            String rootField="onboard:"+f.session.lease().operationId()+":seal";
            String partial=f.pair.redis.hget(generation,rootField); assertNotNull(partial);
            assertEquals("1",partial.split(":")[2]);
            f.pair.redis.set(keys.entityKey(NS,1L),"two"); f.pair.redis.set(last,"last");
            assertThrows(ProtectionException.class,f::seal);
            assertEquals(partial,f.pair.redis.hget(generation,rootField));
            assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
            f.pair.redis.set(keys.entityKey(NS,1L),"one");
            String token=f.seal(); assertEquals(token,f.seal());
            assertEquals("2",f.pair.redis.hget(generation,rootField).split(":")[2]);
            assertEquals(token,f.installer.inventoryToken(f.session.request()));
        }
    }

    @Test void sourceOwnerSnapshotBoundsUnknownMetadataAndTotalPayloadBeforeOwnerOrSealWrites() {
        try (var admin=CacheTenantContext.administrative(); var f=new OwnerFixture(List.of(1L,2L))) {
            var keys=f.pair.a.database.redisKeyStrategy(); String generation=f.pair.a.plan.barrier.generationKey(f.session.lease().key());
            String root="onboard:"+f.session.lease().operationId()+":seal";
            String meta=keys.indexMetaKey(NS,1L), entity=keys.entityKey(NS,1L);
            f.pair.redis.set(entity,"one");
            f.pair.redis.hset(meta,"unknown_"+"x".repeat(10000),"x".repeat(10000));
            assertThrows(ProtectionException.class,f::seal); assertNull(f.pair.redis.hget(generation,root));
            f.pair.redis.del(meta); f.pair.redis.hset(meta,"tenant","x".repeat(1_048_577));
            assertThrows(ProtectionException.class,f::seal); assertNull(f.pair.redis.hget(generation,root));
            f.pair.redis.del(meta); f.pair.redis.set(entity,"x".repeat(600000));
            f.pair.redis.set(keys.entityKey(NS,2L),"y".repeat(600000));
            assertThrows(ProtectionException.class,f::seal); assertNull(f.pair.redis.hget(generation,root));
            assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
            assertNull(f.pair.redis.get(keys.versionKey(NS,2L)+":tenant"));
            assertEquals("CLOSED",f.pair.redis.hget(generation,"state"));
        }
    }

    private static void assertOwnerInstallRejected(OwnerFixture f) {
        assertThrows(ProtectionException.class,()->f.install(f.proofs(7,7)));
        var keys=f.pair.a.database.redisKeyStrategy();
        assertNull(f.pair.redis.get(keys.versionKey(NS,1L)+":tenant"));
        assertNull(f.pair.redis.get(keys.versionKey(NS,2L)+":tenant"));
    }

    private static CacheSourceOwnerOnboarding<Long> ownerAdapter(Pair f,List<Long> candidates) {
        var ids=JdbcSourceOwnerOnboarding.longIds();
        var plan=new SourceOwnerOnboarding.Plan(f.a.plan.binding,META.versionColumn(),ids.formatToken());
        return f.a.database.prepareSourceOwnerOnboarding(META,f.a.enrollment,f.a.plan,f.approval(),ids,candidates,Set.of(plan.fingerprint()));
    }
    private static void seedOwnerSource(Pair f,long id,String tenant) throws Exception {
        try (var connection=f.source.getConnection(); var q=connection.prepareStatement(
                "INSERT INTO "+NS+" (id,tenant,entity_version) VALUES (?,?,4)")) {
            q.setQueryTimeout(2); q.setLong(1,id); q.setString(2,tenant); assertEquals(1,q.executeUpdate());
        }
    }
    private static void assertOwnerSqlStillClosed(Pair f) throws Exception {
        try (var connection=f.source.getConnection()) {
            connection.setAutoCommit(false);
            assertEquals("CDBGC",assertThrows(java.sql.SQLException.class,
                    ()->f.a.plan.guard.authorizeWrite(connection,new Generation(f.lease.key(),1))).getSQLState());
            connection.rollback();
        }
    }
    private static SourceOwnerOnboarding.Checkpoint ownerStepWithActualPeer(Pair f,CacheSourceOwnerOnboarding<Long> prepared,
            SourceOwnerOnboarding.Request request) throws Exception {
        var stop=new java.util.concurrent.atomic.AtomicBoolean(); var executor=Executors.newSingleThreadExecutor();
        Future<?> peer=executor.submit(()->{
            try (var admin=CacheTenantContext.administrative()) {
                while (!stop.get()) {
                    var pending=prepared.pendingClosure();
                    if (pending.isPresent()) {
                        f.b.enrollment.sealClosed(f.b.plan,f.approval(),List.of(pending.get()));
                        f.b.enrollment.pollControl(1,1000);
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
                }
            }
        });
        try { return prepared.step(request); }
        finally { stop.set(true); executor.shutdown(); peer.get(10,TimeUnit.SECONDS); }
    }

    /** Native component proof only: manual OwnerProof values below are NOT provider-source qualification. */
    private static final class OwnerFixture implements AutoCloseable {
        final Pair pair=new Pair(true);
        final SourceOwnerOnboarding.Session session;
        final SourceOwnerOnboarding.Page page;
        final List<SourceOwnerOnboarding.Page> pages;
        final String worklist=ProtectionKey.hash("native-component-worklist-not-universal-history");
        final RedisSourceOwnerInstaller<Long> installer;
        boolean sealed;
        OwnerFixture(List<Long> candidates) {
            var ids=JdbcSourceOwnerOnboarding.longIds();
            var plan=new SourceOwnerOnboarding.Plan(pair.a.plan.binding,META.versionColumn(),ids.formatToken());
            session=new SourceOwnerOnboarding.Session(new SourceOwnerOnboarding.Request(plan,new Generation(pair.lease.key(),1),
                    TENANT,pair.lease.operationId(),ProtectionKey.hash("component-owner-operator"),120),pair.lease);
            pages=CacheSourceOwnerOnboarding.pages(candidates,ids,131072,4096); page=pages.getFirst();
            installer=installer(pages);
        }
        RedisSourceOwnerInstaller<Long> installer(List<SourceOwnerOnboarding.Page> configured) {
            return pair.a.enrollment.sourceOwnerInstaller(pair.a.database,pair.a.plan,pair.lease.key(),
                    JdbcSourceOwnerOnboarding.longIds(),configured,worklist);
        }
        List<SourceOwnerOnboarding.OwnerProof<Long>> proofs(long... versions) {
            assertEquals(page.ids().size(),versions.length); var result=new ArrayList<SourceOwnerOnboarding.OwnerProof<Long>>();
            for (int i=0;i<versions.length;i++) result.add(new SourceOwnerOnboarding.OwnerProof<>(Long.valueOf(page.ids().get(i)),
                    page.ids().get(i),SourceOwnerOnboarding.Ownership.SOURCE_OWNED,versions[i]));
            return List.copyOf(result);
        }
        void refresh() {
            pair.round(); pair.b.enrollment.pollControl(1,5000);
            assertEquals(TenantReadFence.State.CLOSED,pair.round().state());
        }
        SourceOwnerOnboarding.Installed install(List<SourceOwnerOnboarding.OwnerProof<Long>> proofs) {
            if (!sealed) seal(); else refresh();
            return installer.install(session,page,proofs);
        }
        String seal() { refresh(); String token=installer.seal(session); sealed=true; return token; }
        boolean verify() { refresh(); return installer.verify(session,page); }
        public void close() { pair.close(); }
    }

    private static final class Pair implements AutoCloseable {
        final String prefix = "enrollment_" + UUID.randomUUID().toString().replace("-", "");
        final String host = System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1");
        final int port = Integer.getInteger("cachedb.snapshot.redis.port", 16383);
        final JedisPooled redis;
        final TenantRuntimeInitialFixture source;
        final BoundedRedisWire wire;
        Root a, b;
        final int capacity;
        Lease lease;
        Pair(boolean seal) { this(seal,8); }
        Pair(boolean seal,int capacity) { this(seal,capacity,true); }
        Pair(boolean seal,int capacity,boolean closeFixtureLease) {
            this.capacity=capacity;
            JedisPooled openedRedis=null;
            TenantRuntimeInitialFixture openedSource=null;
            BoundedRedisWire openedWire=null;
            try {
                redis=openedRedis=client();
                source=openedSource=TenantRuntimeInitialFixture.create(META);
                wire=openedWire=new BoundedRedisWire(new InetSocketAddress(host,port),null,null,null,null,0,2000,2000,1);
            } catch(RuntimeException|Error failure) {
                TenantRuntimeInitialFixture.closeAfterFailedConstruction(failure,openedWire,openedRedis,openedSource);
                throw failure;
            }
            try {
                a = new Root(this, true, true); b = new Root(this);
                assertEquals(a.plan.manifestToken(), b.plan.manifestToken());
                assertEquals(a.enrollment.descriptor(ENTITY).coverageToken(), b.enrollment.descriptor(ENTITY).coverageToken());
                lease = new Lease(a.enrollment.barrier(ENTITY).key(TENANT), UUID.randomUUID(), UUID.randomUUID(), 1, 120);
                try (var admin = CacheTenantContext.administrative()) {
                    if (closeFixtureLease) a.enrollment.barrier(ENTITY).close(lease, 1);
                    if (seal) sealBoth();
                }
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        JedisPooled client() { return new JedisPooled(host, port); }
        ApprovedRoster approval() { return new ApprovedRoster(ProtectionKey.hash("approved-two-owned-roots:" + prefix),
                a.plan.manifestToken(), a.enrollment.descriptor(ENTITY).coverageToken(), List.of(a.enrollmentId, b.enrollmentId), List.of(lease.key())); }
        void sealBoth() { var approval = approval(); a.seal(approval, lease); b.seal(approval, lease); }
        TenantReadFence.Evidence round() { return a.enrollment.readFence(ENTITY).closeAndDrain(lease, 1, a.plan.manifestToken(), "", 128, 5000); }
        String rosterKey() { return a.enrollment.controlStoreKey(ENTITY); }
        String journalKey() { return a.enrollment.barrier(ENTITY).generationKey(lease.key()) + ":read-closure"; }
        void openRedisControlFixture() {
            try (var admin = CacheTenantContext.administrative()) {
                var barrier = a.enrollment.barrier(ENTITY);
                var allocator = new RedisTenantVersionAllocator(a.database.redisOperations(), a.database.redisKeyStrategy(), barrier, NS);
                // A Redis-only test grant, never an SQL onboarding certificate. No business row/owner is installed.
                allocator.install(new TenantVersionRange(lease.key(), 1, lease.epoch(), 1, 1, 100,
                        UUID.randomUUID(), allocator.runId(lease.key()), ProtectionKey.hash("control-test-only-not-source-inventory")));
                barrier.openPreparedGeneration(lease, 1);
            }
        }
        void closeNextEpoch() {
            lease = new Lease(lease.key(), UUID.randomUUID(), UUID.randomUUID(), lease.epoch() + 1, 120);
            a.enrollment.barrier(ENTITY).close(lease, 1);
        }
        public void close() {
            try { if (b != null) b.close(); } finally {
                try { if (a != null) a.close(); } finally {
                    try {
                        var owned = new HashSet<String>(); String cursor = "0"; int pages = 0;
                        do {
                            if (++pages > 1000) throw new AssertionError("Fixture cleanup page bound");
                            var page = redis.scan(cursor, new redis.clients.jedis.params.ScanParams().match(prefix + ":*").count(128));
                            owned.addAll(page.getResult()); if (owned.size() > 10000) throw new AssertionError("Fixture cleanup key bound");
                            cursor = page.getCursor();
                        } while (!cursor.equals("0"));
                        if (!owned.isEmpty()) redis.del(owned.toArray(String[]::new));
                        redis.functionDelete(prefix + "_functions"); redis.del("cachedb:function-library:" + prefix + "_functions:marker");
                    } finally {
                        try { wire.close(); } finally { try { redis.close(); } finally { source.close(); } }
                    }
                }
            }
        }
    }

    private static final class Root implements AutoCloseable {
        final JedisPooled client;
        final CacheDatabase database;
        final com.reactor.cachedb.core.api.EntityRepository<Row, Long> raw;
        final CacheTenantRuntimeEnrollment enrollment;
        final UUID enrollmentId;
        final RedisTenantPurgePlan plan;
        Root(Pair f) { this(f, true); }
        Root(Pair f, boolean prepared) { this(f, prepared, false); }
        Root(Pair f, boolean prepared, boolean birth) {
            JedisPooled openedClient=null;
            CacheDatabase openedDatabase=null;
            final CacheDatabaseConfig config;
            try {
                client = openedClient=f.client(); var library = f.prefix + "_functions";
                config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .keyspace(KeyspaceConfig.builder().keyPrefix(f.prefix).build())
                        .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                        .redisFunctions(RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library + "_upsert")
                                .deleteFunctionName(library + "_delete").compactionCompleteFunctionName(library + "_complete").build())
                        .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(f.prefix + ":write")
                                .deadLetterStreamKey(f.prefix + ":dlq").compactionStreamKey(f.prefix + ":compact").build()).build();
                database = openedDatabase=new CacheDatabase(client, f.source, config);
            } catch(RuntimeException|Error failure) {
                TenantRuntimeInitialFixture.closeAfterFailedConstruction(failure,openedDatabase,openedClient);
                throw failure;
            }
            try {
                database.start(); database.register(META, CODEC, POLICY);
                if (birth) f.source.bootstrap(database, META, TENANT, f.capacity);
                if (!prepared) {
                    var producer = database.configureTenantProducer(META, f.capacity);
                    enrollmentId = producer.runtimeId();
                    raw = database.repository(META, CODEC, POLICY);
                    enrollment = null; plan = null; return;
                }
                try (var admin = CacheTenantContext.administrative()) { enrollment = database.prepareTenantProtection(f.capacity); }
                raw = database.repository(META, CODEC, POLICY);
                enrollmentId = enrollment.descriptor(ENTITY).runtimeId();
                var target = enrollment.pointProfileTarget(ENTITY);
                var binding = new Binding(Row.class, NS, new SourcePlan(NS, "tenant", "id"),
                        RedisIdentityPurgeParticipant.layoutFingerprint(List.of(target)), 10000, 128, 1048576, true);
                var guard = new JdbcTenantPurgeGuard(f.source, JdbcTenantPurgeDialect.POSTGRES, 2, 1,Set.of(binding.fingerprint()));
                var queues = new TreeMap<String, BoundedRedisWire>();
                queues.put(config.writeBehind().streamKey(), f.wire); queues.put(config.writeBehind().deadLetterStreamKey(), f.wire);
                queues.put(config.writeBehind().compactionStreamKey(), f.wire);
                for (var stream : config.writeBehind().activeStreamKeys()) queues.put(stream, f.wire);
                plan = new RedisTenantPurgePlan(binding, ENTITY, META.versionColumn(), guard,
                        new JdbcTenantVersionAllocator(guard, JdbcTenantPurgeDialect.POSTGRES, 2),
                        new JdbcPurgeScanJournal(guard, JdbcTenantPurgeDialect.POSTGRES, 2, 128, 10000, 10485760),
                        new JdbcPurgeFacadeJournal(guard, JdbcTenantPurgeDialect.POSTGRES, 2, 10000, 10485760),
                        new JdbcPurgeParticipantJournal(guard, 2, 1), f.wire, database.redisOperations(), database.redisKeyStrategy(),
                        enrollment.barrier(ENTITY), enrollment.readFence(ENTITY), List.of(target), Set.of(NS), Map.of(), queues,
                        new PurgeParticipantManifest(ENTITY, Set.of("runtime-control-roster"),
                                List.of(enrollment.controlParticipant(ENTITY))), event -> {});
                database.configureDataProtection(List.of(plan), 1);
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void seal(ApprovedRoster approval, Lease lease) { enrollment.sealClosed(plan, approval, List.of(new ClosedPair(lease, 1))); }
        public void close() { try { database.close(); } finally { client.close(); } }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Owned delivery release timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError("Fixture interrupted"); }
    }
}
