package com.reactor.cachedb.integration;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultEntityRegistryRegressionTest {

    @Test
    void shouldUpgradeNoOpLoadersWhenConcreteRegistrationArrives() {
        DefaultEntityRegistry registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        RelationBatchLoader<RegistryEntity> relationLoader = (entities, context) -> {
        };
        EntityPageLoader<RegistryEntity> pageLoader = pageWindow -> List.of();

        registry.register(METADATA, CODEC, CachePolicy.defaults(), new NoOpRelationBatchLoader<>(), new NoOpEntityPageLoader<>());
        registry.register(METADATA, CODEC, CachePolicy.defaults(), relationLoader, pageLoader);

        var binding = registry.find(METADATA.entityName()).orElseThrow();
        assertSame(relationLoader, binding.relationBatchLoader());
        assertSame(pageLoader, binding.pageLoader());
    }

    @Test
    void shouldNotDowngradeConcreteLoadersBackToNoOp() {
        DefaultEntityRegistry registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        RelationBatchLoader<RegistryEntity> relationLoader = (entities, context) -> {
        };
        EntityPageLoader<RegistryEntity> pageLoader = pageWindow -> List.of();

        registry.register(METADATA, CODEC, CachePolicy.defaults(), relationLoader, pageLoader);
        registry.register(METADATA, CODEC, CachePolicy.defaults(), new NoOpRelationBatchLoader<>(), new NoOpEntityPageLoader<>());

        var binding = registry.find(METADATA.entityName()).orElseThrow();
        assertSame(relationLoader, binding.relationBatchLoader());
        assertSame(pageLoader, binding.pageLoader());
    }

    private static final EntityMetadata<RegistryEntity, Long> METADATA = new EntityMetadata<>() {
        @Override
        public String entityName() {
            return "RegistryEntity";
        }

        @Override
        public String tableName() {
            return "registry_entity";
        }

        @Override
        public String redisNamespace() {
            return "registry-entity";
        }

        @Override
        public String idColumn() {
            return "id";
        }

        @Override
        public Class<RegistryEntity> entityType() {
            return RegistryEntity.class;
        }

        @Override
        public java.util.function.Function<RegistryEntity, Long> idAccessor() {
            return entity -> entity.id;
        }

        @Override
        public List<String> columns() {
            return List.of("id", "name");
        }

        @Override
        public Map<String, String> columnTypes() {
            return Map.of("id", Long.class.getName(), "name", String.class.getName());
        }

        @Override
        public List<com.reactor.cachedb.core.model.RelationDefinition> relations() {
            return List.of();
        }
    };

    private static final EntityCodec<RegistryEntity> CODEC = new EntityCodec<>() {
        @Override
        public String toRedisValue(RegistryEntity entity) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            values.put("id", entity.id == null ? null : String.valueOf(entity.id));
            values.put("name", entity.name);
            return LengthPrefixedPayloadCodec.encode(values);
        }

        @Override
        public RegistryEntity fromRedisValue(String encoded) {
            Map<String, String> values = LengthPrefixedPayloadCodec.decode(encoded);
            RegistryEntity entity = new RegistryEntity();
            entity.id = values.get("id") == null ? null : Long.valueOf(values.get("id"));
            entity.name = values.get("name");
            return entity;
        }

        @Override
        public Map<String, Object> toColumns(RegistryEntity entity) {
            return Map.of("id", entity.id, "name", entity.name);
        }
    };

    private static final class RegistryEntity {
        private Long id;
        private String name;
    }
}
