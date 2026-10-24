package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.core.repository.WindowRequest;
import java.time.Clock;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class SnapshotIndexDeltaRedisTest {
    record Row(String id, String partition, int score, String value) {}

    final String namespace = "snapshot-index-test-" + UUID.randomUUID();
    JedisPooled redis;
    JdbcDataSource sql;
    SnapshotJobs jobs;
    SnapshotPlan<Row, String> plan;

    @BeforeEach
    void setup() throws Exception {
        redis =
                new JedisPooled(
                        "redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":"
                                + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        sql = new JdbcDataSource();
        sql.setURL("jdbc:h2:mem:" + namespace + ";DB_CLOSE_DELAY=-1");
        execute(
                "CREATE TABLE \"records\"(\"id\" VARCHAR PRIMARY KEY,\"partition\""
                        + " VARCHAR,\"score\" INT,\"value\" VARCHAR)");
        execute(
                "INSERT INTO \"records\" VALUES"
                    + " ('a','customer',1,'one'),('b','customer',1,'two'),('c','customer',2,'three')");
        var roots =
                SnapshotSource.entity(
                        "roots",
                        new SourceMapping<Row>(
                                "records",
                                List.of("id", "partition", "score", "value"),
                                r ->
                                        new Row(
                                                (String) r.get("id"),
                                                (String) r.get("partition"),
                                                ((Number) r.get("score")).intValue(),
                                                (String) r.get("value"))));
        plan =
                new SnapshotPlan<>(
                        "catalog",
                        roots,
                        Row::id,
                        List.of(roots),
                        String.class,
                        rows -> r -> List.of(r.value()));
        plan =
                plan.indexed(SnapshotIndexSpec.of(Row::partition, Row::score))
                        .scoped(
                                SnapshotRootScope.builder()
                                        .source(roots, ids -> SnapshotPredicate.inValues("id", ids))
                                        .build())
                        .withContentReuse();
        jobs =
                new SnapshotJobs(
                        sql,
                        redis,
                        new ObjectMapper(),
                        Clock.systemUTC(),
                        namespace,
                        List.of(plan),
                        Map.of());
    }

    @Test
    void scoreRangeIsAppliedBeforePayloadLoadingAndMatchesFixturePagination() {
        jobs.refresh("catalog", true);
        var repository = jobs.repository("catalog");
        var page = repository.findPage(new SnapshotPageRequest("customer", 1, 1, WindowRequest.first(1)));
        assertThat(page.items()).extracting(SnapshotValue::id).containsExactly("a");
        var next = repository.findPage(new SnapshotPageRequest("customer", 1, 1, WindowRequest.after(page.nextCursor(), 1)));
        assertThat(next.items()).extracting(SnapshotValue::id).containsExactly("b");
        assertThat(next.nextCursor()).isNull();
        assertThatThrownBy(() -> repository.findPage(new SnapshotPageRequest("customer", 1, 2,
                WindowRequest.after(page.nextCursor(), 1)))).isInstanceOf(IllegalArgumentException.class);
    }

    void execute(String command) throws Exception {
        try (var c = sql.getConnection();
                var s = c.createStatement()) {
            s.execute(command);
        }
    }

    @AfterEach
    void close() throws Exception {
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

    @Test
    void pagesPreserveTiesAndRejectAnotherGenerationOrPartition() {
        jobs.refresh("catalog", true);
        var repository = jobs.repository("catalog");
        var first = repository.findPage("customer", WindowRequest.first(1));
        assertThat(first.items()).extracting(SnapshotValue::id).containsExactly("a");
        assertThat(repository.findPage("customer", first.nextRequest(2).orElseThrow()).items())
                .extracting(SnapshotValue::id)
                .containsExactly("b", "c");
        assertThatThrownBy(() -> repository.findPage("other", first.nextRequest(1).orElseThrow()))
                .hasMessageContaining("partition");
        jobs.refresh("catalog", true);
        assertThatThrownBy(
                        () -> repository.findPage("customer", first.nextRequest(1).orElseThrow()))
                .hasMessageContaining("cursor expired");
    }

    @Test
    void pageByteBudgetRejectsLargeWindowsWithoutTruncatingResults() {
        jobs.refresh("catalog", true);
        var repository =
                new RedisSnapshotRepository(
                        com.reactor.cachedb.redis.JedisPooledOperations.of(redis),
                        namespace + ":{snapshot:catalog}",
                        true,
                        null,
                        10);
        assertThat(repository.findPage("customer", WindowRequest.first(1)).items()).hasSize(1);
        assertThatThrownBy(() -> repository.findPage("customer", WindowRequest.first(3)))
                .hasMessageContaining("byte budget");
    }

    @Test
    void deltaChangesOnlyRequestedRootsAndInvalidatesCursor() throws Exception {
        assertThatThrownBy(() -> jobs.refreshRoots("catalog", List.of("a")))
                .hasMessageContaining("First complete");
        jobs.refresh("catalog", true);
        var repository = jobs.repository("catalog");
        var first = repository.findPage("customer", WindowRequest.first(1));
        var before = repository.findById("c").orElseThrow();
        execute("UPDATE \"records\" SET \"value\"='updated',\"score\"=3 WHERE \"id\"='a'");
        execute("DELETE FROM \"records\" WHERE \"id\"='b'");
        repository.refreshRoots(List.of("a", "b"));
        assertThat(repository.findById("a").orElseThrow().payload()).isEqualTo("[\"updated\"]");
        assertThat(repository.findById("b")).isEmpty();
        assertThat(repository.findById("c").orElseThrow()).isEqualTo(before);
        assertThat(repository.findPage("customer", WindowRequest.first(10)).items())
                .extracting(SnapshotValue::id)
                .containsExactly("c", "a");
        assertThatThrownBy(
                        () -> repository.findPage("customer", first.nextRequest(1).orElseThrow()))
                .hasMessageContaining("cursor expired");
    }

    @Test void unicodeIdentifiersAndPartitionsProduceReusableBoundedCursors() throws Exception {
        String id="ğ".repeat(500), partition="ö".repeat(500);
        execute("DELETE FROM \"records\"");
        execute("INSERT INTO \"records\" VALUES ('"+id+"a','"+partition+"',1,'one'),('"
                +id+"b','"+partition+"',1,'two')");
        jobs.refresh("catalog",true);
        var repository=jobs.repository("catalog");
        var first=repository.findPage(partition,WindowRequest.first(1));
        assertThat(first.nextRequest(1).orElseThrow().after().length()).isGreaterThan(3000);
        assertThat(repository.findPage(partition,first.nextRequest(1).orElseThrow()).items())
                .extracting(SnapshotValue::id).containsExactly(id+"b");
    }

    @Test
    void repeatedCatalogSharesImmutablePayloadAndFailureKeepsActiveGeneration() throws Exception {
        jobs.refresh("catalog", true);
        var before = jobs.repository("catalog").findById("a").orElseThrow();
        jobs.refresh("catalog", true);
        Set<String> content = new HashSet<>();
        String cursor = "0";
        do {
            var scanned =
                    redis.scan(
                            cursor, new ScanParams().match(namespace + ":*:content:*").count(100));
            cursor = scanned.getCursor();
            content.addAll(scanned.getResult());
        } while (!cursor.equals("0"));
        assertThat(content).hasSize(3);
        assertThat(jobs.repository("catalog").findById("a").orElseThrow().generation())
                .isNotEqualTo(before.generation());
        execute("DROP TABLE \"records\"");
        assertThatThrownBy(() -> jobs.refreshRoots("catalog", List.of("a")))
                .hasMessageContaining("source read failed");
        assertThat(jobs.repository("catalog").findById("a").orElseThrow().payload())
                .isEqualTo(before.payload());
    }

    @Test
    @EnabledIfSystemProperty(named = "cachedb.redis.cluster", matches = "true")
    void indexedAndIncrementalSnapshotsPublishAndReadOnARealCluster() throws Exception {
        var config =
                DefaultJedisClientConfig.builder()
                        .connectionTimeoutMillis(3000)
                        .socketTimeoutMillis(5000)
                        .hostAndPortMapper(
                                address ->
                                        System.getProperty("cachedb.redis.cluster.host") != null ? address :
                                        new HostAndPort(
                                                "127.0.0.1",
                                                address.getPort() < 10000
                                                        ? address.getPort() + 10000
                                                        : address.getPort()))
                        .build();
        try (var cluster =
                new com.reactor.cachedb.redis.JedisClusterOperations(
                        new JedisCluster(Set.of(new HostAndPort(System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"), Integer.getInteger("cachedb.redis.cluster.port", 17001))), config))) {
            jobs.close();
            jobs =
                    new SnapshotJobs(
                            sql,
                            cluster,
                            new ObjectMapper(),
                            Clock.systemUTC(),
                            namespace,
                            List.of(plan),
                            Map.of());
            try {
                jobs.refresh("catalog", true);
                var repository = jobs.repository("catalog");
                assertThat(repository.findById("a")).isPresent();
                assertThat(repository.findPage("customer", WindowRequest.first(2)).items())
                        .hasSize(2);
                execute("DELETE FROM \"records\" WHERE \"id\"='a'");
                repository.refreshRoots(List.of("a"));
                assertThat(repository.findById("a")).isEmpty();
                jobs.refresh("catalog", true);
                assertThat(repository.findPage("customer", WindowRequest.first(10)).items())
                        .hasSize(2);
            } finally {
                jobs.close();
                String cursor = "0";
                do {
                    var result =
                            cluster.scan(
                                    cursor, new ScanParams().match(namespace + ":*").count(100));
                    cursor = result.getCursor();
                    for (String key : result.getResult()) cluster.commands().unlink(key);
                } while (!cursor.equals("0"));
            }
        }
    }
}
