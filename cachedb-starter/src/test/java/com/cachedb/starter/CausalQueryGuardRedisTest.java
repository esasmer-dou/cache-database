package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.exception.CacheCausalReadUnsupportedException;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionCodec;
import com.reactor.cachedb.core.query.AggregateSpec;
import com.reactor.cachedb.core.query.PartitionedQuerySpec;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class CausalQueryGuardRedisTest {
    @Test void uncertifiedReadsFailClosedWithoutWeakeningVersionedEntityReads() {
        try (var fixture = new HotPathEvolutionRedisTest.Fixture(CachePolicy.defaults(), new HotPathEvolutionRedisTest.Source())) {
            var repository = fixture.repository;
            var receipt = repository.saveWithReceipt(new HotPathEvolutionRedisTest.Row(1L, "first", 5));
            try (var scope = CacheCausalContext.withReceipt("evolution", receipt)) {
                assertEquals(1L, repository.findById(1L).orElseThrow().id);
                assertEquals(1, repository.findAll(List.of(1L)).size());
                assertThrows(CacheCausalReadUnsupportedException.class, () -> repository.query(QuerySpec.allOf()));
                assertThrows(CacheCausalReadUnsupportedException.class, () -> repository.count(QuerySpec.allOf()));
                assertThrows(CacheCausalReadUnsupportedException.class, () -> repository.aggregate(AggregateSpec.sum("score", null)));
                assertThrows(CacheCausalReadUnsupportedException.class, () -> repository.aggregateBy("score", AggregateSpec.count(null)));
                assertThrows(CacheCausalReadUnsupportedException.class, () -> repository.findPage(new PageWindow(0, 10)));
                assertThrows(CacheCausalReadUnsupportedException.class, () -> repository.queryPartitions(
                        PartitionedQuerySpec.top("score", List.of(5L), 1, QuerySort.desc("score"))));
                try (var future = CacheCausalContext.withMinVersion("evolution", 1L, receipt.version() + 1)) {
                    assertThrows(IllegalStateException.class, () -> repository.findById(1L));
                    assertThrows(IllegalStateException.class, () -> repository.findAll(List.of(1L)));
                }
            }
            assertEquals(1, repository.query(QuerySpec.allOf()).size());
            assertFalse(CacheCausalContext.hasRequirements());
        }
    }

    @Test void unrelatedNamespaceDoesNotBlockEntityReadsAndNestedScopeRestoresContract() {
        try (var fixture = new HotPathEvolutionRedisTest.Fixture(CachePolicy.defaults(), new HotPathEvolutionRedisTest.Source())) {
            fixture.repository.save(new HotPathEvolutionRedisTest.Row(1L, "first", 5));
            try (var unrelated = CacheCausalContext.withMinVersion("evolution:other", 1L, 9)) {
                assertEquals(1, fixture.repository.query(QuerySpec.allOf()).size());
                try (var own = CacheCausalContext.withMinVersion("evolution", 1L, 1)) {
                    assertThrows(CacheCausalReadUnsupportedException.class, () -> fixture.repository.query(QuerySpec.allOf()));
                }
                assertEquals(1, fixture.repository.query(QuerySpec.allOf()).size());
            }
        }
    }

    @Test void projectionPayloadDoesNotMasqueradeAsAppliedSourceVersion() {
        try (var fixture = new HotPathEvolutionRedisTest.Fixture(CachePolicy.defaults(), new HotPathEvolutionRedisTest.Source())) {
            var receipt = fixture.repository.saveWithReceipt(new HotPathEvolutionRedisTest.Row(1L, "first", 5));
            var projection = fixture.repository.projected(EntityProjection.of("causal-summary",
                    new ProjectionCodec<HotPathEvolutionRedisTest.Row>() {
                        public String toRedisValue(HotPathEvolutionRedisTest.Row row) { return fixture.codec.toRedisValue(row); }
                        public HotPathEvolutionRedisTest.Row fromRedisValue(String raw) { return fixture.codec.fromRedisValue(raw); }
                    }, row -> row.id, HotPathEvolutionRedisTest.METADATA.columns(), fixture.codec::toColumns, row -> row));
            assertTrue(projection.findById(1L).isPresent());
            try (var own = CacheCausalContext.withReceipt("evolution", receipt)) {
                assertThrows(CacheCausalReadUnsupportedException.class, () -> projection.findById(1L));
                assertThrows(CacheCausalReadUnsupportedException.class, () -> projection.findAll(List.of(1L)));
                assertThrows(CacheCausalReadUnsupportedException.class, () -> projection.query(QuerySpec.allOf()));
            }
            assertTrue(projection.findById(1L).isPresent());
        }
    }
}
