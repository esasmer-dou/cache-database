package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.change.ExternalChangeApplyException;
import com.reactor.cachedb.core.change.ExternalChangeApplyMode;
import com.reactor.cachedb.core.change.ExternalChangeApplyResult;
import com.reactor.cachedb.core.change.ExternalChangeApplyStatus;
import com.reactor.cachedb.core.change.ExternalChangeEvent;
import com.reactor.cachedb.core.change.ExternalChangeHydrationRepository;
import com.reactor.cachedb.core.change.ExternalChangeType;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityByIdLoader;
import com.reactor.cachedb.core.page.NoOpEntityQueryLoader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalChangeApplyRunnerTest {

    @Test
    void cacheOnlyUpsertHydratesRedisWithoutWriteBehindSave() {
        Fixture fixture = new Fixture();
        ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
                .builder(fixture.session, fixture.registry)
                .build();

        ExternalChangeApplyResult result = runner.apply(new ExternalChangeEvent(
                "CustomerEntity",
                "42",
                ExternalChangeType.UPSERT,
                Map.of("status", "VIP"),
                7L,
                Instant.parse("2026-06-13T10:00:00Z"),
                "postgres-outbox"
        ));

        assertEquals(ExternalChangeApplyStatus.APPLIED, result.status());
        assertEquals(42L, fixture.repository.externalUpsert.id);
        assertEquals("VIP", fixture.repository.externalUpsert.status);
        assertEquals(7L, fixture.repository.externalUpsertVersion);
        assertNull(fixture.repository.saved);
    }

    @Test
    void cacheOnlyDeleteHydratesRedisTombstoneWithoutWriteBehindDelete() {
        Fixture fixture = new Fixture();
        ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
                .builder(fixture.session, fixture.registry)
                .build();

        ExternalChangeApplyResult result = runner.apply(new ExternalChangeEvent(
                "CustomerEntity",
                "43",
                ExternalChangeType.DELETE,
                Map.of(),
                8L,
                Instant.parse("2026-06-13T10:00:00Z"),
                "postgres-outbox"
        ));

        assertEquals(ExternalChangeApplyStatus.APPLIED, result.status());
        assertEquals(43L, fixture.repository.externalDeleteId);
        assertEquals(8L, fixture.repository.externalDeleteVersion);
        assertNull(fixture.repository.deletedId);
    }

    @Test
    void writeBehindModeUsesNormalRepositoryMutations() {
        Fixture fixture = new Fixture();
        ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
                .builder(fixture.session, fixture.registry)
                .mode(ExternalChangeApplyMode.CACHE_AND_WRITE_BEHIND)
                .build();

        runner.accept(new ExternalChangeEvent(
                "CustomerEntity",
                "44",
                ExternalChangeType.UPSERT,
                Map.of("status", "ACTIVE"),
                9L,
                Instant.parse("2026-06-13T10:00:00Z"),
                "command-bus"
        ));
        runner.accept(new ExternalChangeEvent(
                "CustomerEntity",
                "44",
                ExternalChangeType.DELETE,
                Map.of(),
                10L,
                Instant.parse("2026-06-13T10:00:01Z"),
                "command-bus"
        ));

        assertEquals(44L, fixture.repository.saved.id);
        assertEquals("ACTIVE", fixture.repository.saved.status);
        assertEquals(44L, fixture.repository.deletedId);
        assertNull(fixture.repository.externalUpsert);
        assertNull(fixture.repository.externalDeleteId);
    }

    @Test
    void unknownEntityFailsAcceptSoOutboxCheckpointCannotAdvance() {
        Fixture fixture = new Fixture();
        ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
                .builder(fixture.session, fixture.registry)
                .build();

        ExternalChangeApplyException exception = assertThrows(ExternalChangeApplyException.class, () -> runner.accept(
                new ExternalChangeEvent(
                        "MissingEntity",
                        "99",
                        ExternalChangeType.UPSERT,
                        Map.of("status", "ACTIVE"),
                        1L,
                        Instant.parse("2026-06-13T10:00:00Z"),
                        "postgres-outbox"
                )
        ));

        assertEquals(ExternalChangeApplyStatus.FAILED, exception.result().status());
        assertEquals("MissingEntity", exception.result().entityName());
    }

    @Test
    void customHandlerCanOwnPartialPayloadSemantics() {
        Fixture fixture = new Fixture();
        ExternalChangeApplyResult expected = ExternalChangeApplyResult.applied(
                new ExternalChangeEvent(
                        "CustomerEntity",
                        "45",
                        ExternalChangeType.UPSERT,
                        Map.of("patch", "status-only"),
                        11L,
                        Instant.parse("2026-06-13T10:00:00Z"),
                        "outbox"
                ),
                45L,
                ExternalChangeApplyMode.CACHE_ONLY,
                "handled explicitly"
        );
        ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
                .builder(fixture.session, fixture.registry)
                .handler("CustomerEntity", ignored -> expected)
                .build();

        ExternalChangeApplyResult actual = runner.apply(new ExternalChangeEvent(
                "CustomerEntity",
                "45",
                ExternalChangeType.UPSERT,
                Map.of("patch", "status-only"),
                11L,
                Instant.parse("2026-06-13T10:00:00Z"),
                "outbox"
        ));

        assertSame(expected, actual);
        assertNull(fixture.repository.externalUpsert);
        assertNull(fixture.repository.saved);
    }

    private static final class Fixture {
        private final TestMetadata metadata = new TestMetadata();
        private final TestCodec codec = new TestCodec();
        private final RecordingRepository repository = new RecordingRepository();
        private final EntityBinding<TestEntity, Long> binding = new EntityBinding<>(
                metadata,
                codec,
                CachePolicy.defaults(),
                null,
                null,
                new NoOpEntityByIdLoader<>(),
                new NoOpEntityQueryLoader<>()
        );
        private final EntityRegistry registry = new SingleEntityRegistry(binding);
        private final CacheSession session = new RecordingCacheSession(repository);
    }

    private static final class TestEntity {
        private long id;
        private String status;
    }

    private static final class TestMetadata implements EntityMetadata<TestEntity, Long> {
        @Override
        public String entityName() {
            return "CustomerEntity";
        }

        @Override
        public String tableName() {
            return "customer";
        }

        @Override
        public String redisNamespace() {
            return "customer";
        }

        @Override
        public String idColumn() {
            return "customer_id";
        }

        @Override
        public Class<TestEntity> entityType() {
            return TestEntity.class;
        }

        @Override
        public java.util.function.Function<TestEntity, Long> idAccessor() {
            return entity -> entity.id;
        }

        @Override
        public List<String> columns() {
            return List.of("customer_id", "status");
        }

        @Override
        public List<RelationDefinition> relations() {
            return List.of();
        }
    }

    private static final class TestCodec implements EntityCodec<TestEntity> {
        @Override
        public String toRedisValue(TestEntity entity) {
            return entity.id + ":" + entity.status;
        }

        @Override
        public TestEntity fromRedisValue(String encoded) {
            String[] parts = encoded.split(":", 2);
            TestEntity entity = new TestEntity();
            entity.id = Long.parseLong(parts[0]);
            entity.status = parts.length > 1 ? parts[1] : null;
            return entity;
        }

        @Override
        public Map<String, Object> toColumns(TestEntity entity) {
            LinkedHashMap<String, Object> columns = new LinkedHashMap<>();
            columns.put("customer_id", entity.id);
            columns.put("status", entity.status);
            return columns;
        }

        @Override
        public TestEntity fromColumns(Map<String, Object> columns) {
            TestEntity entity = new TestEntity();
            Object id = columnValue(columns, "customer_id");
            entity.id = id instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(id));
            Object status = columnValue(columns, "status");
            entity.status = status == null ? null : String.valueOf(status);
            return entity;
        }
    }

    private static final class RecordingRepository implements EntityRepository<TestEntity, Long>,
            ExternalChangeHydrationRepository<TestEntity, Long> {
        private TestEntity saved;
        private Long deletedId;
        private TestEntity externalUpsert;
        private long externalUpsertVersion;
        private Long externalDeleteId;
        private long externalDeleteVersion;

        @Override
        public Optional<TestEntity> findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TestEntity> findAll(Collection<Long> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TestEntity> findPage(PageWindow pageWindow) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QueryExplainPlan explain(QuerySpec querySpec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TestEntity> query(QuerySpec querySpec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TestEntity save(TestEntity entity) {
            this.saved = entity;
            return entity;
        }

        @Override
        public void deleteById(Long id) {
            this.deletedId = id;
        }

        @Override
        public EntityRepository<TestEntity, Long> withFetchPlan(FetchPlan fetchPlan) {
            return this;
        }

        @Override
        public <P> ProjectionRepository<P, Long> projected(EntityProjection<TestEntity, P, Long> projection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TestEntity hydrateExternalUpsert(TestEntity entity, long version) {
            this.externalUpsert = entity;
            this.externalUpsertVersion = version;
            return entity;
        }

        @Override
        public void hydrateExternalDelete(Long id, long version) {
            this.externalDeleteId = id;
            this.externalDeleteVersion = version;
        }
    }

    private static final class RecordingCacheSession implements CacheSession {
        private final RecordingRepository repository;

        private RecordingCacheSession(RecordingRepository repository) {
            this.repository = repository;
        }

        @Override
        public <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
            return repository(metadata, codec, CachePolicy.defaults());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, ID> EntityRepository<T, ID> repository(
                EntityMetadata<T, ID> metadata,
                EntityCodec<T> codec,
                CachePolicy cachePolicy
        ) {
            return (EntityRepository<T, ID>) repository;
        }
    }

    private static final class SingleEntityRegistry implements EntityRegistry {
        private final EntityBinding<TestEntity, Long> binding;

        private SingleEntityRegistry(EntityBinding<TestEntity, Long> binding) {
            this.binding = binding;
        }

        @Override
        public <T, ID> EntityBinding<T, ID> register(
                EntityMetadata<T, ID> metadata,
                EntityCodec<T> codec,
                CachePolicy cachePolicy,
                RelationBatchLoader<T> relationBatchLoader,
                EntityPageLoader<T> pageLoader
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T, ID, P> EntityProjectionBinding<T, P, ID> registerProjection(
                EntityMetadata<T, ID> metadata,
                EntityProjection<T, P, ID> projection
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EntityBinding<?, ?>> find(String entityName) {
            return binding.metadata().entityName().equals(entityName) ? Optional.of(binding) : Optional.empty();
        }

        @Override
        public Optional<EntityProjectionBinding<?, ?, ?>> findProjection(String entityName, String projectionName) {
            return Optional.empty();
        }

        @Override
        public Collection<EntityProjectionBinding<?, ?, ?>> projections(String entityName) {
            return List.of();
        }

        @Override
        public Collection<EntityBinding<?, ?>> all() {
            return List.of(binding);
        }
    }
}
