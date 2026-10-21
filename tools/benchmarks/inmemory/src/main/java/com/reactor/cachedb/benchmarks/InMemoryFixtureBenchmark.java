package com.reactor.cachedb.benchmarks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.OptimisticWriteConflictException;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.core.testing.InMemoryCacheDatabase;
import com.reactor.cachedb.core.testing.InMemoryEntityRepository;
import com.reactor.cachedb.spring.boot.snapshot.InMemorySnapshotRepository;
import com.reactor.cachedb.spring.boot.snapshot.SnapshotStatus;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Each invocation includes a fresh session, registration, fixture writes and correctness checks.
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(
        value = 3,
        jvmArgsAppend = {"-Xms128m", "-Xmx256m"})
public class InMemoryFixtureBenchmark {
    @Param({"entity", "batch", "paging", "snapshot"})
    public String scenario;

    @Param({"100"})
    public int rows;

    private ObjectMapper mapper;

    public record Ticket(Long id, String status) {}

    public record Summary(String title) {}

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @Warmup(iterations = 5, time = 1)
    @Measurement(iterations = 5, time = 1)
    public void warmedFixture(Blackhole blackhole) {
        blackhole.consume(runScenario());
    }

    // Intentionally no warmup: first-use class loading and JSON initialization stay visible.
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1)
    @Fork(
            value = 5,
            jvmArgsAppend = {"-Xms128m", "-Xmx256m"})
    public void coldFixture(Blackhole blackhole) {
        blackhole.consume(runScenario());
    }

    private Object runScenario() {
        return switch (scenario) {
            case "entity" -> entity();
            case "batch" -> batch();
            case "paging" -> paging();
            case "snapshot" -> snapshot();
            default -> throw new IllegalArgumentException(scenario);
        };
    }

    private Object entity() {
        var db = new InMemoryCacheDatabase();
        var repository =
                repository(
                        db,
                        CachePolicy.builder()
                                .hotEntityLimit(2)
                                .stateWindow("status", List.of("OPEN"))
                                .build());
        require(repository.findHotById(1L).isNotCached());
        var first = repository.save(new Ticket(1L, "OPEN"), 0);
        require(first.version() == 1);
        conflict(() -> repository.save(new Ticket(1L, "OPEN"), 0));
        repository.save(new Ticket(2L, "CLOSED"));
        require(repository.findHotById(2L).isOutsideHotPolicy());
        repository.save(new Ticket(3L, "OPEN"));
        repository.findById(1L).orElseThrow();
        repository.save(new Ticket(4L, "OPEN"));
        require(repository.findHotById(3L).isNotCached());
        var deleted = repository.deleteWithReceipt(1L);
        require(repository.findHotById(1L).isTombstoned());
        return deleted;
    }

    private Object batch() {
        var db = new InMemoryCacheDatabase();
        var repository = repository(db, CachePolicy.defaults());
        repository.save(new Ticket(1L, "OPEN"));
        conflict(
                () ->
                        db.writeBatch()
                                .put(new Ticket(1L, "CLOSED"), 1)
                                .put(new Ticket(2L, "OPEN"), 1)
                                .commit());
        require(repository.findById(1L).orElseThrow().status().equals("OPEN"));
        require(repository.findById(2L).isEmpty());
        var accepted =
                db.writeBatch("checkout")
                        .put(new Ticket(1L, "CLOSED"), 1)
                        .put(new Ticket(2L, "OPEN"), 0)
                        .commit();
        require(
                accepted
                        == db.writeBatch("checkout")
                                .put(new Ticket(1L, "CLOSED"), 1)
                                .put(new Ticket(2L, "OPEN"), 0)
                                .commit());
        require(repository.findVersionedById(1L).orElseThrow().version() == 2);
        require(!db.isDurable(accepted));
        db.markDurable(accepted);
        require(db.awaitDurable(accepted, Duration.ofMillis(1)));
        return accepted;
    }

    private Object paging() {
        require(rows >= 4);
        var db = new InMemoryCacheDatabase();
        var repository = repository(db, CachePolicy.builder().hotEntityLimit(rows).build());
        for (long id = 1; id <= rows; id++)
            repository.save(new Ticket(id, id % 2 == 0 ? "OPEN" : "CLOSED"));
        var query = QuerySpec.where(QueryFilter.eq("status", "OPEN")).orderBy(QuerySort.desc("id"));
        var first = repository.findCursorPage(query, WindowRequest.first(1));
        var second = repository.findCursorPage(query, first.nextRequest(1).orElseThrow());
        long newest = rows - rows % 2;
        require(first.items().get(0).id() == newest);
        require(second.items().get(0).id() == newest - 2);
        require(repository.query(query.limitTo(2)).size() == 2);
        return second;
    }

    private Object snapshot() {
        // Cold mode includes ObjectMapper construction/type discovery; warmed mode reuses it.
        if (mapper == null) mapper = new ObjectMapper();
        var clock = new MutableClock();
        var raw = new InMemorySnapshotRepository(clock, Duration.ofMinutes(1));
        var typed = raw.typedRepository(mapper, Summary.class);
        require(typed.lookup("a").status() == SnapshotStatus.SNAPSHOT_UNAVAILABLE);
        raw.putSnapshot("a", "[{\"title\":\"hello\"}]", "p", 1);
        raw.putSnapshot("b", "[]", "p", 2);
        require(typed.lookup("a").values().get(0).title().equals("hello"));
        require(typed.lookup("missing").status() == SnapshotStatus.ABSENT_IN_GENERATION);
        clock.now = clock.now.plusSeconds(60);
        require(typed.lookup("a").isStaleWarning());
        require(typed.lookupAll(List.of("a", "missing")).size() == 2);
        var first = typed.findTypedPage("p", WindowRequest.first(1));
        require(
                typed.findTypedPage("p", first.nextRequest(1).orElseThrow())
                        .items()
                        .get(0)
                        .values()
                        .isEmpty());
        raw.expire();
        var unavailable = typed.lookup("a");
        require(!unavailable.isSnapshotAvailable());
        return unavailable;
    }

    private static InMemoryEntityRepository<Ticket, Long> repository(
            InMemoryCacheDatabase db, CachePolicy policy) {
        EntityMetadata<Ticket, Long> metadata =
                new EntityMetadata<>() {
                    public String entityName() {
                        return "Ticket";
                    }

                    public String tableName() {
                        return "tickets";
                    }

                    public String redisNamespace() {
                        return "tickets";
                    }

                    public String idColumn() {
                        return "id";
                    }

                    public Class<Ticket> entityType() {
                        return Ticket.class;
                    }

                    public Function<Ticket, Long> idAccessor() {
                        return Ticket::id;
                    }

                    public List<String> columns() {
                        return List.of("id", "status");
                    }

                    public List<RelationDefinition> relations() {
                        return List.of();
                    }
                };
        EntityCodec<Ticket> codec =
                new EntityCodec<>() {
                    public String toRedisValue(Ticket ticket) {
                        return ticket.id() + "|" + ticket.status();
                    }

                    public Ticket fromRedisValue(String payload) {
                        int delimiter = payload.indexOf('|');
                        return new Ticket(
                                Long.valueOf(payload.substring(0, delimiter)),
                                payload.substring(delimiter + 1));
                    }

                    public Map<String, Object> toColumns(Ticket ticket) {
                        return Map.of("id", ticket.id(), "status", ticket.status());
                    }
                };
        return db.repository(metadata, codec, policy);
    }

    private static void conflict(Runnable write) {
        try {
            write.run();
        } catch (OptimisticWriteConflictException expected) {
            return;
        }
        throw new AssertionError("Expected optimistic conflict");
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Fixture contract failed");
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.ofEpochMilli(1_000_000);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }
}
