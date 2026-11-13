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
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import jdk.jfr.*;
import jdk.jfr.consumer.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

/** Real protected INITIAL/control paths plus same-root ordinary composite constructor I/O, with JFR evidence. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(60)
class CacheTenantRuntimeVirtualThreadRedisTest {
    private static final String ENTITY = "VirtualEnrollmentRow", NS = "virtual_enrollment_rows";
    private static final String CONSTRUCTOR_ENTITY = "VirtualConstructorRow", CONSTRUCTOR_NS = "virtual_constructor_rows";
    private static final List<String> PHASES = List.of("enroll-a", "enroll-b", "seal-a", "seal-b",
            "close-a", "poll-b", "consensus-a", "deliver-a", "deliver-b");

    @Name("com.reactor.cachedb.f3.EnrollmentPhase")
    @Label("F3 completed real runtime phase")
    @StackTrace(true)
    static final class PhaseEvent extends Event {
        String phase;
        boolean virtual;
        int redisCalls;
        int blockingRedisCalls;
    }
    @Name("com.reactor.cachedb.f3.EnrollmentRedisIO")
    @Label("F3 real Redis I/O coverage, not a pinning event")
    @StackTrace(true)
    static final class RedisIOEvent extends Event {
        String phase;
        String entityName;
        boolean virtual;
        boolean blockedOnRedis;
        boolean completed;
        boolean repositoryConstruction;
    }

    @Test void actualVirtualThreadEnrollmentSealPollAndOwnedDeliveryHaveNoCachedbPins() throws Exception {
        assertTrue(FlightRecorder.isAvailable());
        assertTrue(FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .anyMatch(type -> type.getName().equals("jdk.VirtualThreadPinned")));
        var settings = Configuration.create(Path.of(System.getProperty("cachedb.f3.jfr.settings", "../tools/ci/virtual-threads.jfc")));
        assertEquals("true", settings.getSettings().get("jdk.VirtualThreadPinned#enabled"));
        assertEquals("true", settings.getSettings().get("jdk.VirtualThreadPinned#stackTrace"));
        assertEquals("20 ms", settings.getSettings().get("jdk.VirtualThreadPinned#threshold"));
        Path directory = Path.of(System.getProperty("cachedb.f3.jfr.output", "target/f3-enrollment-jfr"));
        Files.createDirectories(directory);
        Path output = directory.resolve("enrollment-vt.jfr");
        try (var f = new Pair(); var recording = new Recording(settings)) {
            recording.enable(PhaseEvent.class).withThreshold(Duration.ZERO).withStackTrace();
            recording.enable(RedisIOEvent.class).withThreshold(Duration.ZERO).withStackTrace();
            recording.setMaxSize(64L * 1024 * 1024); recording.setDuration(Duration.ofSeconds(30));
            recording.start();
            f.a.enrollment = onVirtual("enroll-a", f.a.operations, () -> {
                try (var admin = CacheTenantContext.administrative()) {
                    assertThrows(ProtectionException.class, () -> f.a.database.prepareTenantProtection(0));
                    // First-root namespace birth and enrollment share the measured virtual-thread phase.
                    f.source.bootstrap(f.a.database, META, "virtual-control-fixture", 8);
                    var enrollment = f.a.database.prepareTenantProtection(8);
                    f.a.constructOrdinaryComposite();
                    return enrollment;
                }
            });
            // Only the empty ordinary companion's owned signature is reset, forcing B's real constructor EVAL.
            assertEquals(1L, f.redis.del(f.a.operations.constructorKeys.get(0)));
            f.b.enrollment = onVirtual("enroll-b", f.b.operations, () -> {
                try (var admin = CacheTenantContext.administrative()) {
                    var enrollment = f.b.database.prepareTenantProtection(8);
                    f.b.constructOrdinaryComposite();
                    return enrollment;
                }
            });
            f.a.bindPlan(f); f.b.bindPlan(f);
            assertEquals(f.a.plan.manifestToken(), f.b.plan.manifestToken());
            var key = f.a.enrollment.barrier(ENTITY).key("virtual-control-fixture");
            var lease = new Lease(key, UUID.randomUUID(), UUID.randomUUID(), 1, 120);
            try (var admin = CacheTenantContext.administrative()) { f.a.enrollment.barrier(ENTITY).close(lease, 1); }
            var approval = new ApprovedRoster(ProtectionKey.hash("explicit-vt-roots:" + f.prefix), f.a.plan.manifestToken(),
                    f.a.enrollment.descriptor(ENTITY).coverageToken(),
                    List.of(f.a.enrollment.descriptor(ENTITY).runtimeId(), f.b.enrollment.descriptor(ENTITY).runtimeId()), List.of(key));
            for (var root : List.of(f.a, f.b)) onVirtual(root == f.a ? "seal-a" : "seal-b", root.operations, () -> {
                try (var admin = CacheTenantContext.administrative()) {
                    root.enrollment.sealClosed(root.plan, approval, List.of(new ClosedPair(lease, 1))); return null;
                }
            });
            onVirtual("close-a", f.a.operations, () -> {
                try (var admin = CacheTenantContext.administrative()) {
                    assertEquals(TenantReadFence.State.DRAINING, f.a.round(lease).state()); return null;
                }
            });
            onVirtual("poll-b", f.b.operations, () -> {
                try (var admin = CacheTenantContext.administrative()) {
                    assertEquals(1, f.b.enrollment.pollControl(1, 5000).locallyAcknowledged()); return null;
                }
            });
            onVirtual("consensus-a", f.a.operations, () -> {
                try (var admin = CacheTenantContext.administrative()) {
                    var evidence = f.a.round(lease); assertEquals(TenantReadFence.State.CLOSED, evidence.state());
                    assertEquals(2, evidence.acknowledged());
                    f.a.enrollment.readFence(ENTITY).requireClosed(lease, 1, f.a.plan.manifestToken(), evidence); return null;
                }
            });
            try (var admin = CacheTenantContext.administrative()) {
                var barrier = f.a.enrollment.barrier(ENTITY);
                var allocator = new RedisTenantVersionAllocator(f.a.database.redisOperations(), f.a.database.redisKeyStrategy(), barrier, NS);
                // Redis-only component grant for an empty-result read; NOT a source onboarding receipt.
                allocator.install(new TenantVersionRange(key, 1, 1, 1, 1, 100, UUID.randomUUID(), allocator.runId(key),
                        ProtectionKey.hash("vt-component-only-no-source-inventory")));
                barrier.openPreparedGeneration(lease, 1);
            }
            for (var root : List.of(f.a, f.b)) onVirtual(root == f.a ? "deliver-a" : "deliver-b", root.operations, () -> {
                root.enrollment.deliver(META, "virtual-control-fixture", repository -> repository.findAll(List.of()), rows -> {
                    assertTrue(Thread.currentThread().isVirtual()); assertTrue(rows.isEmpty()); root.deliveries.incrementAndGet();
                }); return null;
            });
            assertEquals(1, f.a.deliveries.get()); assertEquals(1, f.b.deliveries.get()); assertEquals(0, f.source.calls.get());
            recording.stop(); recording.dump(output);
        }
        inspect(output, directory.resolve("enrollment-vt.json"));
    }

    private static <T> T onVirtual(String phase, SlowOperations operations, Callable<T> action) throws Exception {
        var scope = new Phase(phase); operations.phase = scope;
        var task = new FutureTask<T>(() -> {
            assertTrue(Thread.currentThread().isVirtual());
            var event = new PhaseEvent(); event.phase = phase; event.virtual = Thread.currentThread().isVirtual(); event.begin();
            try { return action.call(); }
            finally {
                event.redisCalls = scope.calls.get(); event.blockingRedisCalls = scope.blocks.get(); event.end(); event.commit();
            }
        });
        Thread worker = Thread.ofVirtual().name("cachedb-f3-" + phase).start(task);
        try { return task.get(5, TimeUnit.SECONDS); }
        finally {
            if (!worker.join(Duration.ofSeconds(5))) throw new AssertionError("Physical VT phase did not exit");
            operations.phase = null;
        }
    }

    private static void inspect(Path recording, Path report) throws Exception {
        var phases = new TreeMap<String, Integer>(); var io = new TreeMap<String, Integer>();
        var constructorPhases = new TreeSet<String>();
        int pinned = 0, cachedbPinned = 0, losses = 0, blocks = 0, constructionBlocks = 0, admissionBlocks = 0, namespaceInitializers = 0, events = 0;
        try (var file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                if (++events > 10000) throw new AssertionError("JFR event bound");
                var event = file.readEvent(); String type = event.getEventType().getName();
                if (type.equals("jdk.DataLoss")) losses++;
                else if (type.equals("jdk.VirtualThreadPinned")) {
                    pinned++;
                    assertNotNull(event.getStackTrace(), "Pinning evidence needs complete stacks");
                    if (event.getStackTrace().getFrames().stream().anyMatch(frame ->
                            frame.getMethod().getType().getName().startsWith("com.reactor.cachedb."))) cachedbPinned++;
                } else if (type.equals("com.reactor.cachedb.f3.EnrollmentPhase")) {
                    assertTrue(event.getBoolean("virtual")); assertTrue(event.getThread().isVirtual());
                    assertTrue(event.getInt("redisCalls") > 0);
                    assertEquals(event.getString("phase").startsWith("enroll-") ? 2 : 1, event.getInt("blockingRedisCalls"));
                    phases.merge(event.getString("phase"), 1, Integer::sum);
                } else if (type.equals("com.reactor.cachedb.f3.EnrollmentRedisIO")) {
                    assertTrue(event.getBoolean("virtual")); assertTrue(event.getThread().isVirtual()); assertTrue(event.getBoolean("completed"));
                    String phase = event.getString("phase"); io.merge(phase, 1, Integer::sum);
                    if (event.getBoolean("blockedOnRedis")) blocks++;
                    String required = phase.startsWith("enroll-") ? "prepareTenantProtection"
                            : phase.startsWith("seal-") ? "sealClosed"
                            : phase.startsWith("deliver-") ? "deliver" : phase.startsWith("poll-") ? "pollControl" : "closeAndDrain";
                    assertNotNull(event.getStackTrace());
                    var frames = event.getStackTrace().getFrames();
                    boolean ordinaryConstructor = event.getBoolean("repositoryConstruction");
                    if (ordinaryConstructor) {
                        assertTrue(phase.equals("enroll-a") || phase.equals("enroll-b"));
                        assertEquals(CONSTRUCTOR_ENTITY, event.getString("entityName"));
                        assertTrue(event.getBoolean("blockedOnRedis"));
                        assertTrue(frames.stream().anyMatch(frame -> frame.getMethod().getType().getName()
                                .equals(DefaultCacheSession.class.getName()) && frame.getMethod().getName().equals("createRepository")));
                        assertTrue(frames.stream().anyMatch(frame -> frame.getMethod().getType().getName()
                                .equals(RedisQueryIndexManager.class.getName()) && frame.getMethod().getName().equals("checkCompositeSignature")));
                        constructionBlocks++; constructorPhases.add(phase);
                    } else assertEquals(ENTITY, event.getString("entityName"));
                    boolean namespaceBootstrap = phase.equals("enroll-a") && frames.stream().anyMatch(frame ->
                            frame.getMethod().getType().getName().equals(CacheDatabase.class.getName())
                                    && frame.getMethod().getName().equals("prepareInitialNamespace")
                            || frame.getMethod().getType().getName().equals(CacheInitialNamespace.class.getName())
                                    && (frame.getMethod().getName().equals("bootstrap") || frame.getMethod().getName().equals("descriptor")));
                    if (event.getBoolean("blockedOnRedis") && frames.stream().anyMatch(frame ->
                            frame.getMethod().getType().getName().equals("com.reactor.cachedb.redis.RedisTenantRuntimeAdmission")
                                    && frame.getMethod().getName().equals("<init>"))) {
                        assertTrue(phase.startsWith("enroll-")); admissionBlocks++;
                    }
                    if (frames.stream().anyMatch(frame -> frame.getMethod().getType().getName()
                            .equals(CacheInitialNamespace.class.getName() + "$OnceInitializer")
                            && frame.getMethod().getName().equals("initialize"))) {
                        assertEquals("enroll-a", phase); namespaceInitializers++;
                    }
                    assertTrue(ordinaryConstructor || namespaceBootstrap || frames.stream().anyMatch(frame ->
                            (frame.getMethod().getName().equals(required)
                                    || phase.equals("consensus-a") && frame.getMethod().getName().equals("requireClosed"))
                                    && frame.getMethod().getType().getName().startsWith("com.reactor.cachedb.")), phase);
                }
            }
        }
        assertEquals(new TreeSet<>(PHASES), phases.keySet()); assertEquals(new TreeSet<>(PHASES), io.keySet());
        assertTrue(phases.values().stream().allMatch(count -> count == 1)); assertEquals(PHASES.size() + 2, blocks);
        assertEquals(1, namespaceInitializers, "Real first-root namespace initializer must be recorded on a virtual thread");
        assertEquals(2, admissionBlocks, "Both actual runtime admissions must park on real Redis");
        assertEquals(2, constructionBlocks, "Both same-root ordinary composite repository constructors must park on real Redis");
        assertEquals(new TreeSet<>(List.of("enroll-a", "enroll-b")), constructorPhases);
        assertEquals(0, losses);
        Files.writeString(report, "{\"phases\":9,\"virtualPhases\":9,\"realBlockingRedisCalls\":" + blocks
                + ",\"repositoryConstructionParkingCalls\":" + constructionBlocks
                + ",\"ordinaryCompositeConstructorEntity\":\"" + CONSTRUCTOR_ENTITY + "\""
                + ",\"ordinaryCompositeConstructorPhases\":[\"enroll-a\",\"enroll-b\"]"
                + ",\"protectedInitialEntity\":\"" + ENTITY + "\",\"namespaceBootstrapPhase\":\"enroll-a\""
                + ",\"namespaceBootstrapInitializers\":" + namespaceInitializers + ",\"runtimeAdmissionParkingCalls\":" + admissionBlocks
                + ",\"pinThresholdMillis\":20,\"pinnedEvents\":" + pinned + ",\"cachedbPinnedEvents\":" + cachedbPinned
                + ",\"dataLossEvents\":" + losses + "}\n");
        System.out.println("F3_VT_JFR phases=" + phases.keySet() + " realBlockingRedisCalls=" + blocks
                + " pinnedEvents=" + pinned + " cachedbPinnedEvents=" + cachedbPinned + " dataLoss=" + losses);
        assertEquals(0, cachedbPinned, "CacheDB carrier pinning observed; see completed JFR and phase-correlated real Redis I/O");
    }

    private record Phase(String name, AtomicInteger calls, AtomicInteger blocks, AtomicInteger constructionBlocks) {
        Phase(String name) { this(name, new AtomicInteger(), new AtomicInteger(), new AtomicInteger()); }
    }
    private static final class SlowOperations extends JedisPooled {
        final String probe;
        List<String> constructorKeys = List.of();
        volatile Phase phase;
        SlowOperations(String host, int port, String prefix) { super(host, port); probe = prefix + ":vt-blocking-probe"; }
        @Override public Object eval(String script, List<String> keys, List<String> args) {
            var current = phase;
            if (current == null) return super.eval(script, keys, args);
            assertTrue(Thread.currentThread().isVirtual());
            var event = new RedisIOEvent(); event.phase = current.name(); event.entityName = ENTITY; event.virtual = true; event.begin();
            try {
                event.repositoryConstruction = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        frame.getClassName().equals(DefaultCacheSession.class.getName()) && frame.getMethodName().equals("createRepository")));
                if (event.repositoryConstruction) {
                    assertTrue(current.name().startsWith("enroll-"));
                    assertEquals(constructorKeys, keys, "Only the owned ordinary composite constructor may supply constructor parking");
                    event.entityName = CONSTRUCTOR_ENTITY;
                }
                // Keep enrollment parking on actual admission, not earlier namespace-preflight EVALs.
                boolean admission = current.name().startsWith("enroll-") && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        frame.getClassName().equals("com.reactor.cachedb.redis.RedisTenantRuntimeAdmission") && frame.getMethodName().equals("<init>")));
                boolean first = (!current.name().startsWith("enroll-") || admission) && current.blocks.compareAndSet(0, 1);
                boolean construction = event.repositoryConstruction && current.constructionBlocks.compareAndSet(0, 1);
                if (construction && !first) current.blocks.incrementAndGet();
                if (first || construction) {
                    event.blockedOnRedis = true;
                    // Real bounded socket I/O, isolated to an empty disposable key. Never DEBUG SLEEP or CLIENT PAUSE.
                    assertNull(super.blpop(0.08d, probe));
                }
                Object value = super.eval(script, keys, args); current.calls.incrementAndGet(); event.completed = true; return value;
            } finally { event.end(); event.commit(); }
        }
    }

    record Row(Long id, String tenant) {}
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
        public Set<String> indexedColumns() { return Set.of("id", "tenant"); }
        public Map<String, String> columnTypes() { return Map.of("id", "java.lang.Long", "tenant", "java.lang.String"); }
        public List<RelationDefinition> relations() { return List.of(); }
    };
    record ConstructorRow(Long id, String tenant) {}
    private static final EntityCodec<ConstructorRow> CONSTRUCTOR_CODEC = new EntityCodec<>() {
        public String toRedisValue(ConstructorRow row) { return row.id() + "|" + row.tenant(); }
        public ConstructorRow fromRedisValue(String value) { var parts = value.split("\\|", -1); return new ConstructorRow(Long.valueOf(parts[0]), parts[1]); }
        public Map<String, Object> toColumns(ConstructorRow row) { return Map.of("id", row.id(), "tenant", row.tenant()); }
        public ConstructorRow fromColumns(Map<String, Object> values) { return new ConstructorRow(((Number)values.get("id")).longValue(), (String)values.get("tenant")); }
    };
    // Separate non-purge namespace: constructor-lock coverage, never an INITIAL protected-composite claim.
    private static final EntityMetadata<ConstructorRow, Long> CONSTRUCTOR_META = new EntityMetadata<>() {
        public String entityName() { return CONSTRUCTOR_ENTITY; }
        public String tableName() { return CONSTRUCTOR_NS; }
        public String redisNamespace() { return CONSTRUCTOR_NS; }
        public String idColumn() { return "id"; }
        public Class<ConstructorRow> entityType() { return ConstructorRow.class; }
        public Function<ConstructorRow, Long> idAccessor() { return ConstructorRow::id; }
        public boolean tenantPurgeEnabled() { return false; }
        public List<String> columns() { return List.of("id", "tenant"); }
        public Set<String> indexedColumns() { return Set.of("id", "tenant"); }
        public Map<String, List<String>> compositeExactIndexes() { return Map.of("tenant_id", List.of("tenant", "id")); }
        public Map<String, String> columnTypes() { return Map.of("id", "java.lang.Long", "tenant", "java.lang.String"); }
        public List<RelationDefinition> relations() { return List.of(); }
    };
    private static final class Pair implements AutoCloseable {
        final String prefix = "enrollment_vt_" + UUID.randomUUID().toString().replace("-", "");
        final String host = System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1");
        final int port = Integer.getInteger("cachedb.snapshot.redis.port", 16383);
        final JedisPooled redis;
        final TenantRuntimeInitialFixture source;
        final BoundedRedisWire wire;
        Root a, b;
        Pair() {
            JedisPooled openedRedis=null;
            TenantRuntimeInitialFixture openedSource=null;
            BoundedRedisWire openedWire=null;
            try {
                redis=openedRedis=new JedisPooled(host,port);
                source=openedSource=TenantRuntimeInitialFixture.create(META);
                wire=openedWire=new BoundedRedisWire(new InetSocketAddress(host,port),null,null,null,null,0,2000,2000,1);
            } catch(RuntimeException|Error failure) {
                TenantRuntimeInitialFixture.closeAfterFailedConstruction(failure,openedWire,openedRedis,openedSource);
                throw failure;
            }
            try { a = new Root(this); b = new Root(this); }
            catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
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
        final SlowOperations operations;
        final CacheDatabase database;
        final AtomicInteger deliveries = new AtomicInteger();
        CacheTenantRuntimeEnrollment enrollment;
        RedisTenantPurgePlan plan;
        Root(Pair f) {
            JedisPooled openedClient=null;
            CacheDatabase openedDatabase=null;
            try {
                operations = new SlowOperations(f.host, f.port, f.prefix); client = openedClient=operations;
                String library = f.prefix + "_functions";
                var config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
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
                operations.constructorKeys = List.of(database.redisKeyStrategy().compositeIndexSignatureKey(CONSTRUCTOR_NS),
                        database.redisKeyStrategy().indexAllKey(CONSTRUCTOR_NS), database.redisKeyStrategy().hotSetKey(CONSTRUCTOR_NS));
                database.start(); database.register(META, CODEC, CachePolicy.defaults());
                database.register(CONSTRUCTOR_META, CONSTRUCTOR_CODEC, CachePolicy.defaults());
                assertEquals(List.of(ENTITY), database.entityRegistry().tenantProtectedBindings().stream()
                        .map(binding -> binding.metadata().entityName()).toList());
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void constructOrdinaryComposite() {
            assertInstanceOf(RedisEntityRepository.class, database.repository(CONSTRUCTOR_META, CONSTRUCTOR_CODEC));
        }
        void bindPlan(Pair f) {
            var target = enrollment.pointProfileTarget(ENTITY);
            var binding = new Binding(Row.class, NS, new SourcePlan(NS, "tenant", "id"),
                    RedisIdentityPurgeParticipant.layoutFingerprint(List.of(target)), 10000, 128, 1048576, true);
            var guard = new JdbcTenantPurgeGuard(f.source, JdbcTenantPurgeDialect.POSTGRES, 2, 1);
            var queues = new TreeMap<String, BoundedRedisWire>(); var write = database.config().writeBehind();
            queues.put(write.streamKey(), f.wire); queues.put(write.deadLetterStreamKey(), f.wire); queues.put(write.compactionStreamKey(), f.wire);
            for (var stream : write.activeStreamKeys()) queues.put(stream, f.wire);
            plan = new RedisTenantPurgePlan(binding, ENTITY, META.versionColumn(), guard,
                    new JdbcTenantVersionAllocator(guard, JdbcTenantPurgeDialect.POSTGRES, 2),
                    new JdbcPurgeScanJournal(guard, JdbcTenantPurgeDialect.POSTGRES, 2, 128, 10000, 10485760),
                    new JdbcPurgeFacadeJournal(guard, JdbcTenantPurgeDialect.POSTGRES, 2, 10000, 10485760),
                    new JdbcPurgeParticipantJournal(guard, 2, 1), f.wire, database.redisOperations(), database.redisKeyStrategy(),
                    enrollment.barrier(ENTITY), enrollment.readFence(ENTITY), List.of(target), Set.of(NS), Map.of(), queues,
                    new PurgeParticipantManifest(ENTITY, Set.of("runtime-control-roster"), List.of(enrollment.controlParticipant(ENTITY))), event -> {});
            database.configureDataProtection(List.of(plan), 1);
        }
        TenantReadFence.Evidence round(Lease lease) {
            return enrollment.readFence(ENTITY).closeAndDrain(lease, 1, plan.manifestToken(), "", 128, 5000);
        }
        public void close() { try { database.close(); } finally { client.close(); } }
    }
}

