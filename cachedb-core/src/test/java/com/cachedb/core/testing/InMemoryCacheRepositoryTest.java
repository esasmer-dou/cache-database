package com.reactor.cachedb.core.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.*;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class InMemoryCacheRepositoryTest {
    @Test
    void nullSerializationNeverMutatesAnEntityOrBecomesABatchDelete() {
        var delegate = codec();
        var broken = new java.util.concurrent.atomic.AtomicBoolean();
        EntityCodec<Ticket> codec =
                new EntityCodec<>() {
                    public String toRedisValue(Ticket value) {
                        return broken.get() ? null : delegate.toRedisValue(value);
                    }

                    public Ticket fromRedisValue(String value) {
                        return value == null
                                ? new Ticket(1L, "CLOSED")
                                : delegate.fromRedisValue(value);
                    }

                    public Map<String, Object> toColumns(Ticket value) {
                        return delegate.toColumns(value);
                    }
                };
        var db = new InMemoryCacheDatabase();
        var repository = db.repository(metadata(), codec);
        repository.save(new Ticket(1L, "OPEN"), 0);
        broken.set(true);
        assertThrows(
                NullPointerException.class, () -> repository.save(new Ticket(1L, "CLOSED"), 1));
        assertThrows(
                NullPointerException.class,
                () -> db.writeBatch("invalid").put(new Ticket(1L, "CLOSED"), 1));
        assertEquals("OPEN", repository.findById(1L).orElseThrow().status());
        assertEquals(1, repository.findVersionedById(1L).orElseThrow().version());
        assertTrue(repository.findHotById(1L).isHit());
    }

    record Ticket(Long id, String status) {}

    static EntityMetadata<Ticket, Long> metadata() {
        return new EntityMetadata<>() {
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

            public java.util.function.Function<Ticket, Long> idAccessor() {
                return Ticket::id;
            }

            public List<String> columns() {
                return List.of("id", "status");
            }

            public List<RelationDefinition> relations() {
                return List.of();
            }
        };
    }

    static EntityCodec<Ticket> codec() {
        return new EntityCodec<>() {
            public String toRedisValue(Ticket value) {
                return value.id + "|" + value.status;
            }

            public Ticket fromRedisValue(String value) {
                String[] parts = value.split("\\|", 2);
                return new Ticket(Long.valueOf(parts[0]), parts[1]);
            }

            public Map<String, Object> toColumns(Ticket value) {
                return Map.of("id", value.id, "status", value.status);
            }
        };
    }

    @Test
    void casFourStatesAndLruAreInfrastructureFree() {
        var db = new InMemoryCacheDatabase();
        var repository =
                db.repository(
                        metadata(),
                        codec(),
                        CachePolicy.builder()
                                .hotEntityLimit(2)
                                .stateWindow("status", List.of("OPEN"))
                                .build());
        assertTrue(repository.findHotById(1L).isNotCached());
        var receipt = repository.save(new Ticket(1L, "OPEN"), 0);
        assertEquals(1L, receipt.version());
        assertThrows(
                OptimisticWriteConflictException.class,
                () -> repository.save(new Ticket(1L, "OPEN"), 0));
        repository.save(new Ticket(2L, "CLOSED"));
        assertTrue(repository.findHotById(2L).isOutsideHotPolicy());
        repository.save(new Ticket(3L, "OPEN"));
        repository.findById(1L);
        repository.save(new Ticket(4L, "OPEN"));
        assertTrue(repository.findHotById(3L).isNotCached());
        repository.deleteById(1L);
        assertTrue(repository.findHotById(1L).isTombstoned());
        assertEquals(2L, repository.save(new Ticket(2L, "OPEN"), 1).version());
    }

    @Test
    void atomicConflictDoesNotMutateAnyMemberAndDurabilityIsExplicit() {
        var db = new InMemoryCacheDatabase();
        var repository = db.repository(metadata(), codec());
        repository.save(new Ticket(1L, "OPEN"));
        var invalid =
                db.writeBatch().put(new Ticket(1L, "CLOSED"), 1).put(new Ticket(2L, "OPEN"), 1);
        assertThrows(OptimisticWriteConflictException.class, invalid::commit);
        assertEquals("OPEN", repository.findById(1L).orElseThrow().status());
        assertTrue(repository.findById(2L).isEmpty());
        var batch = db.writeBatch().put(new Ticket(1L, "CLOSED"), 1).put(new Ticket(2L, "OPEN"), 0);
        var receipt = batch.commit();
        assertEquals(receipt, batch.commit());
        assertFalse(db.isDurable(receipt));
        db.markDurable(receipt);
        assertTrue(db.awaitDurable(receipt, Duration.ofMillis(5)));
    }

    @Test
    void queryAndPagingUseTheSameFiltersAndTieBreakers() {
        var db = new InMemoryCacheDatabase();
        var repository = db.repository(metadata(), codec());
        repository.saveAll(
                List.of(new Ticket(1L, "OPEN"), new Ticket(2L, "OPEN"), new Ticket(3L, "CLOSED")));
        var query = QuerySpec.where(QueryFilter.eq("status", "OPEN"));
        var first = repository.findCursorPage(query, WindowRequest.first(1));
        assertEquals(1L, first.items().get(0).id());
        assertEquals(
                2L,
                repository
                        .findCursorPage(query, first.nextRequest(1).orElseThrow())
                        .items()
                        .get(0)
                        .id());
        assertEquals(2, repository.query(query).size());
    }

    @Test
    void durabilityWaitPreservesInterruptionAndHasMonotonicTimeout() {
        var db = new InMemoryCacheDatabase();
        var receipt = db.repository(metadata(), codec()).saveWithReceipt(new Ticket(1L, "OPEN"));
        assertFalse(db.awaitDurable(receipt, Duration.ofMillis(1)));
        Thread.currentThread().interrupt();
        try {
            assertFalse(db.awaitDurable(receipt, Duration.ofSeconds(5)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertThrows(IllegalArgumentException.class, () -> db.awaitDurable(receipt, Duration.ZERO));
    }

    @Test
    void aRepeatedBatchIdCannotApplyAgainOrChangeItsMeaning() {
        var db = new InMemoryCacheDatabase();
        var repository = db.repository(metadata(), codec());
        var first = db.writeBatch("same").put(new Ticket(1L, "OPEN"), 0).commit();
        assertEquals(first, db.writeBatch("same").put(new Ticket(1L, "OPEN"), 0).commit());
        assertEquals(1L, repository.findVersionedById(1L).orElseThrow().version());
        assertThrows(
                IllegalArgumentException.class,
                () -> db.writeBatch("same").put(new Ticket(1L, "CLOSED"), 0).commit());
    }

    @Test
    void generatedBatchIdsAreSessionLocalAndDoNotReplayOtherBatches() {
        var db = new InMemoryCacheDatabase();
        var repository = db.repository(metadata(), codec());
        var first = db.writeBatch().put(new Ticket(1L, "OPEN"), 0).commit();
        db.idGenerator().nextUuid();
        var second = db.writeBatch().put(new Ticket(2L, "OPEN"), 0).commit();
        assertNotEquals(first.batchId(), second.batchId());
        assertEquals(2, repository.findAll(List.of(1L, 2L)).size());
        assertEquals(1L, repository.findVersionedById(1L).orElseThrow().version());
    }

    @Test
    void equivalentPoliciesRemainAcceptedButDifferentPoliciesAreRejected() {
        var db = new InMemoryCacheDatabase();
        var repository = db.repository(metadata(), codec(), CachePolicy.defaults());
        assertSame(repository, db.repository(metadata(), codec(), CachePolicy.defaults()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        db.repository(
                                metadata(),
                                codec(),
                                CachePolicy.builder().hotEntityLimit(2).build()));
    }

    @Test
    void sortingExtractsColumnsOncePerRowAndKeepsTieBreakersAndOffsets() {
        AtomicInteger columns = new AtomicInteger();
        var delegate = codec();
        EntityCodec<Ticket> counting =
                new EntityCodec<>() {
                    public String toRedisValue(Ticket value) {
                        return delegate.toRedisValue(value);
                    }

                    public Ticket fromRedisValue(String value) {
                        return delegate.fromRedisValue(value);
                    }

                    public Map<String, Object> toColumns(Ticket value) {
                        columns.incrementAndGet();
                        return delegate.toColumns(value);
                    }
                };
        var repository = new InMemoryCacheDatabase().repository(metadata(), counting);
        for (long id = 1; id <= 64; id++)
            repository.save(new Ticket(id, id % 2 == 0 ? "OPEN" : "CLOSED"));
        columns.set(0);
        var page =
                repository.query(
                        QuerySpec.builder()
                                .sort(QuerySort.desc("status"))
                                .offset(1)
                                .limit(3)
                                .build());
        assertEquals(List.of(12L, 14L, 16L), page.stream().map(Ticket::id).toList());
        // Count-only admission needs no columns; sorting/filtering extracts each row once.
        assertEquals(64, columns.get());
        assertTrue(repository.query(QuerySpec.builder().offset(100).limit(3).build()).isEmpty());
    }

    @Test
    void expiredEntriesAreNotDecoded() {
        class MutableClock extends Clock {
            Instant now = Instant.EPOCH;

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
        var clock = new MutableClock();
        AtomicInteger decodes = new AtomicInteger();
        var delegate = codec();
        EntityCodec<Ticket> counting =
                new EntityCodec<>() {
                    public String toRedisValue(Ticket value) {
                        return delegate.toRedisValue(value);
                    }

                    public Ticket fromRedisValue(String value) {
                        decodes.incrementAndGet();
                        return delegate.fromRedisValue(value);
                    }

                    public Map<String, Object> toColumns(Ticket value) {
                        return delegate.toColumns(value);
                    }
                };
        var repository =
                new InMemoryCacheDatabase(clock)
                        .repository(
                                metadata(),
                                counting,
                                CachePolicy.builder().entityTtlSeconds(1).build());
        repository.save(new Ticket(1L, "OPEN"));
        decodes.set(0);
        clock.now = clock.now.plusSeconds(1);
        assertTrue(repository.findHotById(1L).isNotCached());
        assertEquals(0, decodes.get());
    }

    @Test
    void mutationFailureRestoresVersionsAndLruEvictions() {
        var db = new InMemoryCacheDatabase();
        var repository =
                db.repository(
                        metadata(),
                        codec(),
                        CachePolicy.builder()
                                .hotEntityLimit(2)
                                .customHotPredicate(
                                        columns -> {
                                            if ("BROKEN".equals(columns.get("status")))
                                                throw new IllegalStateException("fixture failure");
                                            return true;
                                        })
                                .build());
        repository.save(new Ticket(1L, "OPEN"));
        repository.save(new Ticket(2L, "OPEN"));
        repository.findById(1L);
        var batch =
                db.writeBatch("rollback")
                        .put(new Ticket(3L, "OPEN"), 0)
                        .put(new Ticket(4L, "BROKEN"), 0);
        assertThrows(IllegalStateException.class, batch::commit);
        repository.save(new Ticket(5L, "OPEN"));
        assertTrue(repository.findById(2L).isEmpty());
        assertTrue(repository.findById(1L).isPresent());
        assertTrue(repository.findById(3L).isEmpty());
        assertEquals(1, repository.save(new Ticket(3L, "OPEN"), 0).version());
        assertEquals(1, repository.save(new Ticket(4L, "OPEN"), 0).version());
    }

    @Test
    void projectionSortingExtractsColumnsOncePerProjectedRow() {
        var db = new InMemoryCacheDatabase();
        var repository = db.repository(metadata(), codec());
        for (long id = 1; id <= 64; id++)
            repository.save(new Ticket(id, id % 2 == 0 ? "OPEN" : "CLOSED"));
        AtomicInteger columns = new AtomicInteger();
        var delegate = codec();
        var projection =
                com.reactor.cachedb.core.projection.EntityProjection.<Ticket, Ticket, Long>of(
                        "summaries",
                        new com.reactor.cachedb.core.projection.ProjectionCodec<Ticket>() {
                            public String toRedisValue(Ticket value) {
                                return delegate.toRedisValue(value);
                            }

                            public Ticket fromRedisValue(String value) {
                                return delegate.fromRedisValue(value);
                            }
                        },
                        Ticket::id,
                        List.of("id", "status"),
                        value -> {
                            columns.incrementAndGet();
                            return delegate.toColumns(value);
                        },
                        value -> value);
        var result =
                repository
                        .projected(projection)
                        .query(
                                QuerySpec.where(QueryFilter.eq("status", "OPEN"))
                                        .orderBy(QuerySort.desc("status"), QuerySort.desc("id"))
                                        .limitTo(3));
        assertEquals(List.of(64L, 62L, 60L), result.stream().map(Ticket::id).toList());
        assertEquals(64, columns.get());
    }

    @Test
    void countOnlyAdmissionStillHonorsWriteSourcePolicy() {
        var db = new InMemoryCacheDatabase();
        var repository =
                db.repository(
                        metadata(),
                        codec(),
                        CachePolicy.builder()
                                .hotPolicy(
                                        EntityHotPolicy.builder()
                                                .mode(EntityHotPolicyMode.COUNT_WINDOW)
                                                .admitOnWrite(false)
                                                .build())
                                .build());
        var accepted = repository.save(new Ticket(1L, "OPEN"), 0);
        assertEquals(1, accepted.version());
        assertTrue(repository.findHotById(1L).isOutsideHotPolicy());
        assertEquals(2, repository.save(new Ticket(1L, "OPEN"), 1).version());
    }
}
