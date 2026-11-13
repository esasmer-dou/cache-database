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
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

/** Two actual JVM/root incarnations; killing a peer cannot retire it or acknowledge a new lease. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
@Timeout(60)
class CacheTenantRuntimeProcessRedisTest {
    private static final String ENTITY = "ProcessEnrollmentRow", NS = "process_enrollment_rows";

    @Test void forcedPeerDeathCannotAcknowledgeReplacementLeaseOrRetireAfterFreshnessExpires() throws Exception {
        String prefix = "enrollment_process_" + UUID.randomUUID().toString().replace("-", "");
        String host = System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1");
        int port = Integer.getInteger("cachedb.snapshot.redis.port", 16383);
        String operation = UUID.randomUUID().toString(), owner = UUID.randomUUID().toString();
        try (var source = TenantRuntimeInitialFixture.create(META)) {
            try (var a = new Child(host, port, prefix, operation, owner, source.schema, true);
                    var b = new Child(host, port, prefix, operation, owner, source.schema, false)) {
                String[] first = a.ready.split(" "), second = b.ready.split(" ");
                assertEquals("READY", first[0]); assertEquals("READY", second[0]);
                assertNotEquals(first[1], second[1]);
                assertEquals(first[2], second[2]); assertEquals(first[3], second[3]);
                String seal = "SEAL " + first[1] + " " + second[1];
                assertEquals("SEALED", a.command(seal)); assertEquals("SEALED", b.command(seal));
                assertEquals("DRAINING 2 1 INCOMPLETE_INVENTORY", a.command("ROUND"));
                assertEquals("ACK 1", b.command("POLL"));
                assertEquals("CLOSED 2 2 CLOSED", a.command("ROUND"));
                b.kill(); assertFalse(b.process.isAlive());
                assertEquals("REPLACED", a.command("REPLACE"));
                assertEquals("DRAINING 2 1 INCOMPLETE_INVENTORY", a.command("ROUND"));
                Thread.sleep(1100); // Exceed the existing 1000ms ACK freshness; never a retirement proof.
                assertEquals("DRAINING 2 1 INCOMPLETE_INVENTORY", a.command("ROUND"));
                assertEquals("MEMBER 1 TTL -1 SQL 0", a.command("MEMBER " + second[1]));
                try (var fresh = new Child(host, port, prefix, operation, owner, source.schema, false)) {
                    String freshId = fresh.ready.split(" ")[1];
                    assertNotEquals(first[1], freshId); assertNotEquals(second[1], freshId);
                    assertEquals("BLOCKED 2 SQL 0", fresh.command("RAW"));
                    assertEquals("INCOMPLETE", a.command("ROUND"));
                    assertEquals("REGISTRY REVIEW_REQUIRED 3 1", a.command("REGISTRY " + freshId));
                    assertEquals("MEMBER 1 TTL -1 SQL 0", a.command("MEMBER " + second[1]));
                }
            }
        } finally {
            try (var redis = new JedisPooled(host, port)) {
                var owned = new HashSet<String>(); String cursor = "0"; int pages = 0;
                do {
                    if (++pages > 1000) throw new AssertionError("Fixture cleanup page bound");
                    var page = redis.scan(cursor, new redis.clients.jedis.params.ScanParams().match(prefix + ":*").count(128));
                    owned.addAll(page.getResult()); if (owned.size() > 10000) throw new AssertionError("Fixture cleanup key bound");
                    cursor = page.getCursor();
                } while (!cursor.equals("0"));
                if (!owned.isEmpty()) redis.del(owned.toArray(String[]::new));
                redis.functionDelete(prefix + "_functions"); redis.del("cachedb:function-library:" + prefix + "_functions:marker");
            }
        }
    }

    private static final class Child implements AutoCloseable {
        final Process process;
        final BufferedReader output;
        final BufferedWriter input;
        final ExecutorService reader = Executors.newSingleThreadExecutor();
        final String ready;
        boolean terminationRequested, terminationConfirmed;
        Child(String host, int port, String prefix, String operation, String owner, String schema, boolean birth) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            process = new ProcessBuilder(java, "-Xmx96m", "-XX:ActiveProcessorCount=1",
                    "-Dcachedb.initial.isolatedSql=" + Boolean.getBoolean("cachedb.initial.isolatedSql"), "-cp", cp,
                    Peer.class.getName(), host, Integer.toString(port), prefix, operation, owner, schema, Boolean.toString(birth))
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            output = process.inputReader(StandardCharsets.UTF_8); input = process.outputWriter(StandardCharsets.UTF_8);
            String initial;
            try { initial = line(30); }
            catch (Exception | Error failure) {
                try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            ready = initial;
        }
        String command(String command) throws Exception { input.write(command); input.newLine(); input.flush(); return line(); }
        String line() throws Exception { return line(5); }
        String line(int seconds) throws Exception {
            String line = reader.submit(output::readLine).get(seconds, TimeUnit.SECONDS);
            if (line == null || line.length() > 512) throw new AssertionError("Missing or excessive peer fixture reply");
            return line;
        }
        void kill() {
            terminationRequested = true;
            process.destroyForcibly();
            if (!awaitExit()) throw new AssertionError("Owned child process did not exit");
            terminationConfirmed = true;
        }
        private boolean awaitExit() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean interrupted = false;
            try {
                while (process.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return false;
                    try { if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return true; }
                    catch (InterruptedException failure) { interrupted = true; }
                }
                return true;
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        private static Throwable append(Throwable original, Throwable cleanup) {
            if (original == null) return cleanup;
            original.addSuppressed(cleanup); return original;
        }
        public void close() throws Exception {
            Throwable failure = null;
            if (process.isAlive()) {
                try {
                    input.write("EXIT"); input.newLine(); input.flush();
                    terminationRequested = true;
                    if (awaitExit()) terminationConfirmed = true;
                } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            }
            if (process.isAlive()) {
                try { kill(); } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
            }
            // Never send EOF before the requested forced death has physically completed.
            if (!process.isAlive()) {
                try { output.close(); } catch (Exception | Error cleanup) { failure = append(failure, cleanup); }
                try { input.close(); }
                catch (IOException closedPipe) {
                    if (!terminationRequested || !terminationConfirmed) failure = append(failure, closedPipe);
                } catch (RuntimeException | Error cleanup) { failure = append(failure, cleanup); }
            }
            try { reader.shutdownNow(); } catch (RuntimeException | Error cleanup) { failure = append(failure, cleanup); }
            try {
                if (!reader.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("Owned peer reader did not exit");
            } catch (InterruptedException cleanup) {
                Thread.currentThread().interrupt(); failure = append(failure, cleanup);
            } catch (RuntimeException | Error cleanup) { failure = append(failure, cleanup); }
            if (failure instanceof Exception checked) throw checked;
            if (failure instanceof Error fatal) throw fatal;
        }
    }

    public static final class Peer {
        public static void main(String[] args) throws Exception {
            try (var root = new Root(args[0], Integer.parseInt(args[1]), args[2], args[3], args[4], args[5], Boolean.parseBoolean(args[6]));
                    var commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                var descriptor = root.enrollment.descriptor(ENTITY);
                System.out.println("READY " + descriptor.runtimeId() + " " + descriptor.coverageToken() + " " + root.plan.manifestToken());
                System.out.flush();
                for (String line; (line = commands.readLine()) != null;) {
                    if (line.equals("EXIT")) return;
                    try (var admin = CacheTenantContext.administrative()) {
                        if (line.startsWith("SEAL ")) {
                            var fields = line.split(" ");
                            var approval = new ApprovedRoster(ProtectionKey.hash("approved-process-roots:" + args[2]),
                                    root.plan.manifestToken(), descriptor.coverageToken(),
                                    List.of(UUID.fromString(fields[1]), UUID.fromString(fields[2])), List.of(root.lease.key()));
                            root.enrollment.sealClosed(root.plan, approval, List.of(new ClosedPair(root.lease, 1)));
                            System.out.println("SEALED");
                        } else if (line.equals("POLL")) {
                            System.out.println("ACK " + root.enrollment.pollControl(1, 5000).locallyAcknowledged());
                        } else if (line.equals("REPLACE")) {
                            root.lease = new Lease(root.lease.key(), UUID.randomUUID(), UUID.randomUUID(), root.lease.epoch() + 1, 120);
                            root.enrollment.barrier(ENTITY).close(root.lease, 1); System.out.println("REPLACED");
                        } else if (line.equals("ROUND")) {
                            var fence = root.enrollment.readFence(ENTITY);
                            TenantReadFence.Evidence evidence;
                            try { evidence = fence.closeAndDrain(root.lease, 1, root.plan.manifestToken(), "", 128, 5000); }
                            catch (ProtectionException failure) { System.out.println("INCOMPLETE"); System.out.flush(); continue; }
                            String verified = "CLOSED";
                            try { fence.requireClosed(root.lease, 1, root.plan.manifestToken(), evidence); }
                            catch (ProtectionException failure) { verified = failure.code().name(); }
                            System.out.println(evidence.state() + " " + evidence.registered() + " " + evidence.acknowledged() + " " + verified);
                        } else if (line.equals("RAW")) {
                            var repo = root.database.repository(META, CODEC, CachePolicy.defaults()); int blocked = 0;
                            try (var tenant = CacheTenantContext.withTenant("process-fixture")) {
                                try { repo.findAll(List.of()); } catch (RuntimeException rejected) { blocked++; }
                                try { repo.saveWithReceipt(new Row(99L, "process-fixture")); }
                                catch (RuntimeException rejected) { blocked++; }
                            }
                            System.out.println("BLOCKED " + blocked + " SQL " + root.source.calls.get());
                        } else if (line.startsWith("REGISTRY ")) {
                            String key = root.enrollment.controlStoreKey(ENTITY);
                            System.out.println("REGISTRY " + root.redis.hget(key,"state") + " " + root.redis.hget(key,"enrolled")
                                    + " " + root.redis.hget(key,"e:" + UUID.fromString(line.substring(9))));
                        } else if (line.startsWith("MEMBER ")) {
                            String key = root.enrollment.controlStoreKey(ENTITY);
                            System.out.println("MEMBER " + root.redis.hget(key, "m:" + UUID.fromString(line.substring(7)))
                                    + " TTL " + root.redis.pttl(key) + " SQL " + root.source.calls.get());
                        } else throw new AssertionError("Unknown fixture command");
                    }
                    System.out.flush();
                }
            }
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
        public Set<String> indexedColumns() { return Set.of("tenant"); }
        public Map<String, String> columnTypes() { return Map.of("id", "java.lang.Long", "tenant", "java.lang.String"); }
        public List<RelationDefinition> relations() { return List.of(); }
    };

    private static final class Root implements AutoCloseable {
        final JedisPooled redis;
        final TenantRuntimeInitialFixture source;
        final BoundedRedisWire wire;
        final CacheDatabase database;
        final CacheTenantRuntimeEnrollment enrollment;
        final RedisTenantPurgePlan plan;
        Lease lease;
        Root(String host, int port, String prefix, String operation, String owner, String schema, boolean birth) {
            source = TenantRuntimeInitialFixture.attach(schema);
            JedisPooled openedRedis=null;
            BoundedRedisWire openedWire=null;
            CacheDatabase openedDatabase=null;
            final CacheDatabaseConfig config;
            try {
                redis = openedRedis=new JedisPooled(host, port);
                wire = openedWire=new BoundedRedisWire(new InetSocketAddress(host, port), null, null, null, null, 0, 2000, 2000, 1);
                String library = prefix + "_functions";
                config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                        .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                        .redisGuardrail(RedisGuardrailConfig.builder().enabled(false).build())
                        .redisFunctions(RedisFunctionsConfig.builder().libraryName(library).upsertFunctionName(library + "_upsert")
                                .deleteFunctionName(library + "_delete").compactionCompleteFunctionName(library + "_complete").build())
                        .writeBehind(WriteBehindConfig.builder().enabled(false).streamKey(prefix + ":write")
                                .deadLetterStreamKey(prefix + ":dlq").compactionStreamKey(prefix + ":compact").build()).build();
                database = openedDatabase=new CacheDatabase(redis, source, config);
            } catch(RuntimeException|Error failure) {
                TenantRuntimeInitialFixture.closeAfterFailedConstruction(failure,openedDatabase,openedWire,openedRedis);
                throw failure;
            }
            try {
                database.start(); database.register(META, CODEC, CachePolicy.defaults());
                if (birth) source.bootstrap(database, META, "process-fixture", 8);
                try (var admin = CacheTenantContext.administrative()) { enrollment = database.prepareTenantProtection(8); }
                var target = enrollment.pointProfileTarget(ENTITY);
                var binding = new Binding(Row.class, NS, new SourcePlan(NS, "tenant", "id"),
                        RedisIdentityPurgeParticipant.layoutFingerprint(List.of(target)), 10000, 128, 1048576, true);
                var guard = new JdbcTenantPurgeGuard(source, JdbcTenantPurgeDialect.POSTGRES, 2, 1);
                var queues = new TreeMap<String, BoundedRedisWire>();
                queues.put(config.writeBehind().streamKey(), wire); queues.put(config.writeBehind().deadLetterStreamKey(), wire);
                queues.put(config.writeBehind().compactionStreamKey(), wire);
                for (var stream : config.writeBehind().activeStreamKeys()) queues.put(stream, wire);
                plan = new RedisTenantPurgePlan(binding, ENTITY, META.versionColumn(), guard,
                        new JdbcTenantVersionAllocator(guard, JdbcTenantPurgeDialect.POSTGRES, 2),
                        new JdbcPurgeScanJournal(guard, JdbcTenantPurgeDialect.POSTGRES, 2, 128, 10000, 10485760),
                        new JdbcPurgeFacadeJournal(guard, JdbcTenantPurgeDialect.POSTGRES, 2, 10000, 10485760),
                        new JdbcPurgeParticipantJournal(guard, 2, 1), wire, database.redisOperations(), database.redisKeyStrategy(),
                        enrollment.barrier(ENTITY), enrollment.readFence(ENTITY), List.of(target), Set.of(NS), Map.of(), queues,
                        new PurgeParticipantManifest(ENTITY, Set.of("runtime-control-roster"), List.of(enrollment.controlParticipant(ENTITY))), event -> {});
                database.configureDataProtection(List.of(plan), 1);
                lease = new Lease(enrollment.barrier(ENTITY).key("process-fixture"), UUID.fromString(operation), UUID.fromString(owner), 1, 120);
                try (var admin = CacheTenantContext.administrative()) {
                    if (!redis.exists(enrollment.barrier(ENTITY).generationKey(lease.key()))) enrollment.barrier(ENTITY).close(lease, 1);
                }
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        public void close() { try { database.close(); } finally { try { wire.close(); } finally { try { redis.close(); } finally { source.close(); } } } }
    }
}

