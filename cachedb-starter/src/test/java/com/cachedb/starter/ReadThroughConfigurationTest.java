package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfigOverrides;
import com.reactor.cachedb.core.config.ReadThroughConfig;
import com.reactor.cachedb.core.config.ReadThroughMode;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.page.EntityByIdLoader;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.page.NoOpEntityByIdLoader;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityQueryLoader;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadThroughConfigurationTest {

    @Test
    void shouldApplyReadThroughSystemPropertyOverrides() {
        CacheDatabaseConfig base = CacheDatabaseConfig.builder()
                .readThrough(ReadThroughConfig.builder()
                        .mode(ReadThroughMode.REDIS_ONLY)
                        .failOnMissingLoader(false)
                        .hydrateLoadedEntities(true)
                        .maxQueryLoadRows(100)
                        .build())
                .build();
        Properties properties = new Properties();
        properties.setProperty("cachedb.config.readThrough.mode", "READ_THROUGH_QUERY");
        properties.setProperty("cachedb.config.readThrough.failOnMissingLoader", "true");
        properties.setProperty("cachedb.config.readThrough.hydrateLoadedEntities", "false");
        properties.setProperty("cachedb.config.readThrough.maxQueryLoadRows", "75");

        CacheDatabaseConfig resolved = CacheDatabaseConfigOverrides.apply(
                base,
                properties,
                CacheDatabaseConfigOverrides.DEFAULT_PREFIX
        );

        assertEquals(ReadThroughMode.READ_THROUGH_QUERY, resolved.readThrough().mode());
        assertTrue(resolved.readThrough().failOnMissingLoader());
        assertFalse(resolved.readThrough().hydrateLoadedEntities());
        assertEquals(75, resolved.readThrough().maxQueryLoadRows());
    }

    @Test
    void shouldPreserveReadThroughLoadersWhenBindingIsUpdatedWithNoOpLoaders() {
        DefaultEntityRegistry registry = new DefaultEntityRegistry(CacheDatabaseConfig.defaults().resourceLimits());
        TestMetadata metadata = new TestMetadata();
        TestCodec codec = new TestCodec();
        EntityByIdLoader<TestEntity, Long> byIdLoader = id -> Optional.of(new TestEntity(id, "loaded"));
        EntityQueryLoader<TestEntity> queryLoader = ignored -> List.of(new TestEntity(1L, "loaded"));

        EntityBinding<TestEntity, Long> registered = registry.register(
                metadata,
                codec,
                CachePolicy.defaults(),
                new NoOpRelationBatchLoader<>(),
                new NoOpEntityPageLoader<>(),
                byIdLoader,
                queryLoader
        );
        EntityBinding<TestEntity, Long> updated = registry.register(
                metadata,
                codec,
                null,
                new NoOpRelationBatchLoader<>(),
                new NoOpEntityPageLoader<>(),
                new NoOpEntityByIdLoader<>(),
                new NoOpEntityQueryLoader<>()
        );

        assertSame(byIdLoader, registered.byIdLoader());
        assertSame(queryLoader, registered.queryLoader());
        assertSame(byIdLoader, updated.byIdLoader());
        assertSame(queryLoader, updated.queryLoader());
    }

    @Test
    void shouldDefaultLegacyRegistrationToNoOpReadThroughLoaders() {
        DefaultEntityRegistry registry = new DefaultEntityRegistry(CacheDatabaseConfig.defaults().resourceLimits());
        EntityBinding<TestEntity, Long> binding = registry.register(
                new TestMetadata(),
                new TestCodec(),
                CachePolicy.defaults(),
                new NoOpRelationBatchLoader<>(),
                new NoOpEntityPageLoader<>()
        );

        assertInstanceOf(NoOpEntityByIdLoader.class, binding.byIdLoader());
        assertInstanceOf(NoOpEntityQueryLoader.class, binding.queryLoader());
    }

    @Test
    void shouldNormalizeAndValidateWarmPlan() {
        CacheWarmPlan plan = CacheWarmPlan.builder("OrderEntity")
                .maxRows(50)
                .querySpec(QuerySpec.builder().limit(25).build())
                .build();

        assertEquals("warm-OrderEntity", plan.name());
        assertEquals("OrderEntity", plan.entityName());
        assertEquals(25, plan.querySpec().limit());
        assertEquals(50, plan.maxRows());
        assertThrows(IllegalArgumentException.class, () -> CacheWarmPlan.builder(" ").build());
    }

    private record TestEntity(long id, String status) {
    }

    private static final class TestMetadata implements EntityMetadata<TestEntity, Long> {
        @Override
        public String entityName() {
            return "TestEntity";
        }

        @Override
        public String tableName() {
            return "test_entities";
        }

        @Override
        public String redisNamespace() {
            return "test-entities";
        }

        @Override
        public String idColumn() {
            return "id";
        }

        @Override
        public Class<TestEntity> entityType() {
            return TestEntity.class;
        }

        @Override
        public Function<TestEntity, Long> idAccessor() {
            return TestEntity::id;
        }

        @Override
        public List<String> columns() {
            return List.of("id", "status");
        }

        @Override
        public List<RelationDefinition> relations() {
            return List.of();
        }
    }

    private static final class TestCodec implements EntityCodec<TestEntity> {
        @Override
        public String toRedisValue(TestEntity entity) {
            return entity.id() + "|" + entity.status();
        }

        @Override
        public TestEntity fromRedisValue(String encoded) {
            String[] parts = encoded.split("\\|", -1);
            return new TestEntity(Long.parseLong(parts[0]), parts[1]);
        }

        @Override
        public Map<String, Object> toColumns(TestEntity entity) {
            return Map.of("id", entity.id(), "status", entity.status());
        }
    }
}
