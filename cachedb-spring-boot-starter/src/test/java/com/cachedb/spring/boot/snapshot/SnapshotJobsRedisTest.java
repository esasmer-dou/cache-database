package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import javax.sql.DataSource;

/** Uses an isolated namespace, never FLUSHDB, and never the application database. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class SnapshotJobsRedisTest {
    @TempDir Path directory;
    private String namespace;
    private String prefix;
    private JedisPooled redis;
    private DataSource source;
    private Sql sql;
    private final List<SnapshotJobs> opened = new ArrayList<>();
    private final SnapshotSource<Row> root =
            new SnapshotSource<>(
                    "roots",
                    "SELECT id, payload FROM records ORDER BY id",
                    row -> new Row(row.get("ID").toString(), row.get("PAYLOAD").toString()));

    record Row(String id, String payload) {}

    @BeforeEach
    void setup() {
        namespace = "snapshot-test-" + UUID.randomUUID();
        prefix = namespace + ":{snapshot:catalog}";
        redis =
                new JedisPooled(
                        "redis://127.0.0.1:"
                                + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + namespace + ";DB_CLOSE_DELAY=-1");
        source = ds;
        sql = new Sql(source);
        sql.execute("CREATE TABLE records(id VARCHAR PRIMARY KEY, payload VARCHAR)");
        sql.update("INSERT INTO records VALUES ('a','old'),('b','other')");
    }

    private SnapshotJobs job() {
        return job(rows -> row -> List.of(row.payload()), Map.of(), redis);
    }

    private SnapshotJobs job(
            Function<SnapshotRows, Function<Row, Iterable<String>>> mapping,
            Map<String, Object> overrides,
            JedisPooled client) {
        var settings = new HashMap<String, Object>(overrides);
        settings.put("spool-directory", directory.toString());
        var plan =
                new SnapshotPlan<>("catalog", root, Row::id, List.of(root), String.class, mapping);
        var jobs =
                new SnapshotJobs(
                        source,
                        client,
                        new ObjectMapper(),
                        Clock.systemUTC(),
                        namespace,
                        List.of(plan),
                        Map.of("catalog", SnapshotContractTest.settings(settings)));
        opened.add(jobs);
        return jobs;
    }

    private String read(SnapshotJobs jobs, String id) {
        return jobs.repository("catalog").findById(id).orElseThrow().payload();
    }

    @Test
    void publishesFullGenerationAndRemovesDeletedRoots() {
        var job = job();
        assertThat(job.repository("catalog").findById("a")).isEmpty();
        assertThat(job.refresh("catalog", true).submittedRows()).isEqualTo(2);
        var first = job.repository("catalog").findById("a").orElseThrow();
        sql.update("UPDATE records SET payload='new' WHERE id='a'");
        sql.update("DELETE FROM records WHERE id='b'");
        job.refresh("catalog", true);
        assertThat(read(job, "a")).isEqualTo("[\"new\"]");
        assertThat(job.repository("catalog").findById("b")).isEmpty();
        assertThat(redis.exists(prefix + ":data:" + first.generation())).isFalse();
        assertThat(redis.ttl(prefix + ":active")).isBetween(3590L, 3600L);
    }

    @Test
    void emptyGenerationReplacesPreviousCatalog() {
        var job = job();
        job.refresh("catalog", true);
        sql.update("DELETE FROM records");
        assertThat(job.refresh("catalog", true).submittedRows()).isZero();
        assertThat(job.repository("catalog").findById("a")).isEmpty();
    }

    @Test
    void sourceFailureKeepsLastGoodGeneration() {
        var job = job();
        job.refresh("catalog", true);
        sql.execute("DROP TABLE records");
        assertThatThrownBy(() -> job.refresh("catalog", true))
                .hasMessageContaining("source read failed");
        assertThat(read(job, "a")).isEqualTo("[\"old\"]");
        assertThat(redis.exists(prefix + ":lock")).isFalse();
    }

    @Test
    void sourceRowAndByteBudgetsRejectWithoutReplacingGoodData() {
        var good = job();
        good.refresh("catalog", true);
        for (var settings :
                List.of(
                        Map.<String, Object>of("max-source-rows", 1),
                        Map.<String, Object>of("max-source-size", "1B"),
                        Map.<String, Object>of("max-rows-per-source", 1))) {
            var limited = job(rows -> row -> List.of(row.payload()), settings, redis);
            assertThatThrownBy(() -> limited.refresh("catalog", true))
                    .hasMessageContaining("budget");
            assertThat(read(good, "a")).isEqualTo("[\"old\"]");
        }
    }

    @Test
    void preparationFailureAndTimeoutRemoveTemporaryFiles() throws Exception {
        var good = job();
        good.refresh("catalog", true);
        var failed =
                job(
                        rows -> {
                            throw new IllegalStateException("invalid business data");
                        },
                        Map.of(),
                        redis);
        assertThatThrownBy(() -> failed.refresh("catalog", true))
                .hasMessageContaining("invalid business");
        var slow =
                job(
                        rows ->
                                row -> {
                                    try {
                                        Thread.sleep(150);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return List.of(row.payload());
                                },
                        Map.of("preparation-timeout", "PT0.1S", "timeout", "PT1S"),
                        redis);
        assertThatThrownBy(() -> slow.refresh("catalog", true)).hasMessageContaining("timed out");
        assertThat(read(good, "a")).isEqualTo("[\"old\"]");
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void batchTargetsNeverTruncateLargeRows() {
        String payload = "x".repeat(600000);
        sql.update("UPDATE records SET payload=? WHERE id='a'", payload);
        var job =
                job(
                        rows -> row -> List.of(row.payload()),
                        Map.of("batch-target-size", "1KB"),
                        redis);
        assertThat(job.refresh("catalog", true).submittedRows()).isEqualTo(2);
        assertThat(read(job, "a")).isEqualTo("[\"" + payload + "\"]");
    }

    @Test
    void readersSeeOldCatalogUntilAllBatchesCommit() {
        var good = job();
        good.refresh("catalog", true);
        sql.update("UPDATE records SET payload='new'");
        var client = spy(redis);
        AtomicInteger staged = new AtomicInteger();
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            String script = call.getArgument(0);
                            if (script.contains("HSET")) {
                                staged.incrementAndGet();
                                assertThat(read(good, "a")).isEqualTo("[\"old\"]");
                                assertThat(read(good, "b")).isEqualTo("[\"other\"]");
                            }
                            return result;
                        })
                .when(client)
                .eval(anyString(), anyList(), anyList());
        var updating = job(rows -> row -> List.of(row.payload()), Map.of("batch-rows", 1), client);
        updating.refresh("catalog", true);
        assertThat(staged.get()).isEqualTo(2);
        assertThat(read(good, "a")).isEqualTo("[\"new\"]");
    }

    @Test
    void failedSecondBatchCannotExposePartialCatalog() throws Exception {
        var good = job();
        good.refresh("catalog", true);
        var client = spy(redis);
        var count = new AtomicInteger();
        doAnswer(
                        call -> {
                            if (((String) call.getArgument(0)).contains("HSET")
                                    && count.incrementAndGet() == 2)
                                throw new IllegalStateException("transport failure");
                            return call.callRealMethod();
                        })
                .when(client)
                .eval(anyString(), anyList(), anyList());
        sql.update("UPDATE records SET payload='new'");
        var failed = job(rows -> row -> List.of(row.payload()), Map.of("batch-rows", 1), client);
        assertThatThrownBy(() -> failed.refresh("catalog", true))
                .hasMessageContaining("transport failure");
        assertThat(read(good, "a")).isEqualTo("[\"old\"]");
        assertThat(redis.keys(prefix + ":data:*")).hasSize(1);
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void uncertainCommitResponseNeverDeletesTheCommittedGeneration() {
        var good = job();
        good.refresh("catalog", true);
        var client = spy(redis);
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            if (((String) call.getArgument(0)).contains("local previous="))
                                throw new IllegalStateException("reply lost");
                            return result;
                        })
                .when(client)
                .eval(anyString(), anyList(), anyList());
        sql.update("UPDATE records SET payload='new'");
        var uncertain = job(rows -> row -> List.of(row.payload()), Map.of(), client);
        assertThatThrownBy(() -> uncertain.refresh("catalog", true))
                .hasMessageContaining("reply lost");
        assertThat(read(good, "a")).isEqualTo("[\"new\"]");
    }

    @Test
    void expiredOwnerCannotOverwriteOrUnlockNewOwnersPublication() throws Exception {
        var prepared = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var first =
                job(
                        rows -> {
                            prepared.countDown();
                            try {
                                if (!resume.await(10, TimeUnit.SECONDS))
                                    throw new IllegalStateException("test wait");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return row -> List.of(row.payload());
                        },
                        Map.of(),
                        redis);
        var second = job();
        try (var executor = new TestExecutor()) {
            var pending = executor.submit(() -> first.refresh("catalog", true));
            try {
                assertThat(prepared.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(first.refresh("catalog", true).status()).isEqualTo("BUSY");
                assertThat(second.refresh("catalog", true).status()).isEqualTo("BUSY");
                redis.del(prefix + ":lock"); // Simulated lease expiry, isolated test key only.
                sql.update("UPDATE records SET payload='new'");
                second.refresh("catalog", true);
                redis.set(prefix + ":lock", "next-owner");
            } finally {
                resume.countDown();
            }
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(read(second, "a")).isEqualTo("[\"new\"]");
            assertThat(redis.get(prefix + ":lock")).isEqualTo("next-owner");
        }
    }

    @Test
    void leaseIsRenewedDuringSlowPreparation() throws Exception {
        var prepared = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var first =
                job(
                        rows -> {
                            prepared.countDown();
                            try {
                                resume.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return row -> List.of(row.payload());
                        },
                        Map.of("lease-duration", "PT3S"),
                        redis);
        try (var executor = new TestExecutor()) {
            var pending = executor.submit(() -> first.refresh("catalog", true));
            try {
                assertThat(prepared.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(3500);
                assertThat(redis.pttl(prefix + ":lock")).isPositive();
                assertThat(job().refresh("catalog", true).status()).isEqualTo("BUSY");
            } finally {
                resume.countDown();
            }
            assertThat(pending.get(5, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
        }
    }

    @Test
    void periodicExecutionAndManualExecutionUseSameCompletionMarker() throws Exception {
        var job = job();
        job.start();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (redis.get(prefix + ":completed-at") == null && System.nanoTime() < end)
            Thread.sleep(20);
        assertThat(read(job, "a")).isEqualTo("[\"old\"]");
        var second = job();
        assertThat(second.refresh("catalog", false).status()).isEqualTo("NOT_DUE");
        assertThat(second.refresh("catalog", true).status()).isEqualTo("COMPLETED");
        job.close();
        assertThatThrownBy(() -> job.refresh("catalog", true)).hasMessageContaining("closed");
    }

    @AfterEach
    void cleanup() throws Exception {
        for (var job : opened) job.close();
        if (redis != null) {
            String cursor = "0";
            do {
                var page = redis.scan(cursor, new ScanParams().match(namespace + ":*").count(200));
                if (!page.getResult().isEmpty())
                    redis.unlink(page.getResult().toArray(String[]::new));
                cursor = page.getCursor();
            } while (!cursor.equals("0"));
            redis.close();
        }
        if (sql != null) sql.execute("SHUTDOWN");
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    private record Sql(DataSource source) {
        void execute(String query) {
            update(query);
        }

        void update(String query, Object... args) {
            try (var connection = source.getConnection();
                    var statement = connection.prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                statement.execute();
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class TestExecutor implements AutoCloseable {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();

        <T> Future<T> submit(Callable<T> callable) {
            return delegate.submit(callable);
        }

        @Override
        public void close() {
            delegate.shutdownNow();
        }
    }
}
