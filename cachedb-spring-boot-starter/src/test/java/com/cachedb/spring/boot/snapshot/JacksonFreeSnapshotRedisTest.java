package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.json.JsonCodecLimits;
import com.reactor.cachedb.json.StreamingJsonCodec;
import com.reactor.cachedb.redis.JedisPooledOperations;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class JacksonFreeSnapshotRedisTest {
    record Row(String id, int score, String title) {}

    final String namespace = "json-codec-test-" + UUID.randomUUID();
    JedisPooled redis;
    JdbcDataSource sql;
    SnapshotJobs jobs;
    SnapshotSource<Row> roots;

    @BeforeEach
    void setup() throws Exception {
        redis =
                new JedisPooled(
                        "redis://127.0.0.1:"
                                + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        sql = new JdbcDataSource();
        sql.setURL("jdbc:h2:mem:" + namespace + ";DB_CLOSE_DELAY=-1");
        execute(
                "CREATE TABLE \"records\"(\"id\" VARCHAR PRIMARY KEY, \"score\" INT, \"title\""
                        + " VARCHAR)");
        execute(
                "INSERT INTO \"records\" VALUES"
                        + " ('a',1,'\u0130\u011f\ud83d\ude80'),('b',1,'EMPTY'),('c',2,'three')");
        roots =
                SnapshotSource.entity(
                        "roots",
                        new SourceMapping<>(
                                "records",
                                List.of("id", "score", "title"),
                                row ->
                                        new Row(
                                                (String) row.get("id"),
                                                ((Number) row.get("score")).intValue(),
                                                (String) row.get("title"))));
    }

    void execute(String command) throws Exception {
        try (var c = sql.getConnection();
                var s = c.createStatement()) {
            s.execute(command);
        }
    }

    SnapshotPlan<Row, JacksonFreeSnapshotTest.Summary> plan(boolean streaming) {
        java.util.function.Function<Row, Iterable<JacksonFreeSnapshotTest.Summary>> projection =
                row ->
                        row.title().equals("EMPTY")
                                ? List.of()
                                : List.of(new JacksonFreeSnapshotTest.Summary(row.title()));
        var plan =
                streaming
                        ? SnapshotPlan.streaming(
                                "catalog",
                                roots,
                                Row::id,
                                JacksonFreeSnapshotTest.Summary.class,
                                projection)
                        : new SnapshotPlan<>(
                                "catalog",
                                roots,
                                Row::id,
                                List.of(roots),
                                JacksonFreeSnapshotTest.Summary.class,
                                rows -> projection);
        return plan.indexed(SnapshotIndexSpec.of(row -> "p", Row::score))
                .scoped(
                        SnapshotRootScope.builder()
                                .source(roots, ids -> SnapshotPredicate.inValues("id", ids))
                                .build())
                .withContentReuse();
    }

    void start(SnapshotPlan<?, ?> plan) {
        var operations = JedisPooledOperations.of(redis);
        jobs =
                SnapshotJobs.jacksonFree(
                        sql,
                        operations,
                        operations,
                        Clock.systemUTC(),
                        namespace,
                        List.of(plan),
                        Map.of());
    }

    @AfterEach
    void cleanup() throws Exception {
        if (jobs != null) jobs.close();
        if (redis != null) {
            String cursor = "0";
            do {
                var result =
                        redis.scan(cursor, new ScanParams().match(namespace + ":*").count(100));
                cursor = result.getCursor();
                for (String key : result.getResult()) redis.unlink(key);
            } while (!cursor.equals("0"));
            redis.close();
        }
        execute("DROP ALL OBJECTS");
        execute("SHUTDOWN");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void warmAndDeltaPublishTypedValuesWithoutMapperAndNeverReadSqlOnLookup(boolean streaming)
            throws Exception {
        start(plan(streaming).withJsonCodec(new JacksonFreeSnapshotTest.SummaryCodec()));
        TypedSnapshotRepository<String, JacksonFreeSnapshotTest.Summary> typed =
                jobs.typedRepository("catalog");
        assertEquals(SnapshotStatus.SNAPSHOT_UNAVAILABLE, typed.lookup("a").status());
        jobs.refresh("catalog", true);
        assertEquals("\u0130\u011f\ud83d\ude80", typed.lookup("a").values().get(0).title());
        assertTrue(typed.lookup("b").isPresent());
        assertTrue(typed.lookup("b").values().isEmpty());
        assertEquals(SnapshotStatus.ABSENT_IN_GENERATION, typed.lookup("missing").status());
        var page = typed.findTypedPage("p", WindowRequest.first(1));
        var untouched = jobs.repository("catalog").findById("c").orElseThrow();
        execute("UPDATE \"records\" SET \"title\"='updated',\"score\"=3 WHERE \"id\"='a'");
        execute("DELETE FROM \"records\" WHERE \"id\"='b'");
        jobs.refreshRoots("catalog", List.of("a", "b"));
        assertEquals("updated", typed.lookup("a").values().get(0).title());
        assertEquals(SnapshotStatus.ABSENT_IN_GENERATION, typed.lookup("b").status());
        assertEquals(untouched, jobs.repository("catalog").findById("c").orElseThrow());
        assertTrue(
                assertThrows(
                                RuntimeException.class,
                                () -> typed.findTypedPage("p", page.nextRequest(1).orElseThrow()))
                        .getMessage()
                        .contains("cursor expired"));
        execute("DROP TABLE \"records\"");
        assertEquals("updated", typed.lookup("a").values().get(0).title());
        assertEquals(2, typed.lookupAll(List.of("a", "c")).size());
    }

    @Test
    void codecFailureCannotReplaceTheActiveGeneration() throws Exception {
        var fail = new AtomicBoolean();
        var delegate = new JacksonFreeSnapshotTest.SummaryCodec();
        var codec =
                new StreamingJsonCodec<JacksonFreeSnapshotTest.Summary>() {
                    public Class<JacksonFreeSnapshotTest.Summary> valueType() {
                        return JacksonFreeSnapshotTest.Summary.class;
                    }

                    public JacksonFreeSnapshotTest.Summary readValue(
                            JsonReader reader, JsonCodecLimits limits) throws IOException {
                        return delegate.readValue(reader, limits);
                    }

                    public void writeValue(
                            JsonWriter writer,
                            JacksonFreeSnapshotTest.Summary value,
                            JsonCodecLimits limits)
                            throws IOException {
                        if (fail.get()) throw new IOException("Injected codec failure");
                        delegate.writeValue(writer, value, limits);
                    }
                };
        start(plan(true).withJsonCodec(codec));
        jobs.refresh("catalog", true);
        var before = jobs.repository("catalog").findById("a").orElseThrow();
        fail.set(true);
        assertThrows(RuntimeException.class, () -> jobs.refresh("catalog", true));
        assertEquals(before, jobs.repository("catalog").findById("a").orElseThrow());
        fail.set(false);
        jobs.refreshRoots("catalog", List.of("a"));
        assertEquals(
                before.payload(), jobs.repository("catalog").findById("a").orElseThrow().payload());
    }

    @Test
    void missingCodecFailsBeforeStartingAnyWorker() {
        var failure = assertThrows(IllegalArgumentException.class, () -> start(plan(false)));
        assertTrue(failure.getMessage().contains("withJsonCodec"));
    }
}
