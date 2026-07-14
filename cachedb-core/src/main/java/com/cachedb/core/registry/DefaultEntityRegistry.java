package com.reactor.cachedb.core.registry;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityByIdLoader;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.page.NoOpEntityByIdLoader;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityQueryLoader;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.core.relation.RelationBatchLoader;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class DefaultEntityRegistry implements EntityRegistry {

    private final ResourceLimits resourceLimits;
    private final Map<String, EntityBinding<?, ?>> bindings = new LinkedHashMap<>();
    private final Map<String, Map<String, EntityProjectionBinding<?, ?, ?>>> projectionBindings = new LinkedHashMap<>();

    public DefaultEntityRegistry(ResourceLimits resourceLimits) {
        this.resourceLimits = resourceLimits;
    }

    @Override
    public synchronized <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader
    ) {
        return EntityRegistry.super.register(metadata, codec, cachePolicy, relationBatchLoader, pageLoader);
    }

    @Override
    public synchronized <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader,
            EntityByIdLoader<T, ID> byIdLoader,
            EntityQueryLoader<T> queryLoader
    ) {
        EntityBinding<?, ?> existing = bindings.get(metadata.entityName());
        RelationBatchLoader<T> safeLoader = relationBatchLoader == null ? new NoOpRelationBatchLoader<>() : relationBatchLoader;
        EntityPageLoader<T> safePageLoader = pageLoader == null ? new NoOpEntityPageLoader<>() : pageLoader;
        EntityByIdLoader<T, ID> safeByIdLoader = byIdLoader == null ? new NoOpEntityByIdLoader<>() : byIdLoader;
        EntityQueryLoader<T> safeQueryLoader = queryLoader == null ? new NoOpEntityQueryLoader<>() : queryLoader;
        if (existing != null) {
            @SuppressWarnings("unchecked")
            EntityBinding<T, ID> casted = (EntityBinding<T, ID>) existing;
            CachePolicy resolvedPolicy = cachePolicy != null ? cachePolicy : casted.cachePolicy();
            RelationBatchLoader<T> resolvedLoader = selectRelationBatchLoader(casted.relationBatchLoader(), safeLoader);
            EntityPageLoader<T> resolvedPageLoader = selectPageLoader(casted.pageLoader(), safePageLoader);
            EntityByIdLoader<T, ID> resolvedByIdLoader = selectByIdLoader(casted.byIdLoader(), safeByIdLoader);
            EntityQueryLoader<T> resolvedQueryLoader = selectQueryLoader(casted.queryLoader(), safeQueryLoader);
            EntityBinding<T, ID> updated = new EntityBinding<>(
                    metadata,
                    codec,
                    resolvedPolicy,
                    resolvedLoader,
                    resolvedPageLoader,
                    resolvedByIdLoader,
                    resolvedQueryLoader
            );
            bindings.put(metadata.entityName(), updated);
            return updated;
        }
        if (bindings.size() >= resourceLimits.maxRegisteredEntities()) {
            throw new IllegalStateException("Maximum registered entity limit exceeded: " + resourceLimits.maxRegisteredEntities());
        }
        EntityBinding<T, ID> binding = new EntityBinding<>(
                metadata,
                codec,
                cachePolicy,
                safeLoader,
                safePageLoader,
                safeByIdLoader,
                safeQueryLoader
        );
        bindings.put(metadata.entityName(), binding);
        return binding;
    }

    @Override
    public synchronized <T, ID, P> EntityProjectionBinding<T, P, ID> registerProjection(
            EntityMetadata<T, ID> metadata,
            EntityProjection<T, P, ID> projection
    ) {
        Map<String, EntityProjectionBinding<?, ?, ?>> entityProjections = projectionBindings.computeIfAbsent(
                metadata.entityName(),
                key -> new LinkedHashMap<>()
        );
        EntityProjectionBinding<T, P, ID> binding = new EntityProjectionBinding<>(metadata.entityName(), projection);
        entityProjections.put(projection.name(), binding);
        return binding;
    }

    private <T> RelationBatchLoader<T> selectRelationBatchLoader(
            RelationBatchLoader<T> existingLoader,
            RelationBatchLoader<T> candidateLoader
    ) {
        if (!(candidateLoader instanceof NoOpRelationBatchLoader)) {
            return candidateLoader;
        }
        return existingLoader == null ? candidateLoader : existingLoader;
    }

    private <T> EntityPageLoader<T> selectPageLoader(
            EntityPageLoader<T> existingLoader,
            EntityPageLoader<T> candidateLoader
    ) {
        if (!(candidateLoader instanceof NoOpEntityPageLoader)) {
            return candidateLoader;
        }
        return existingLoader == null ? candidateLoader : existingLoader;
    }

    private <T, ID> EntityByIdLoader<T, ID> selectByIdLoader(
            EntityByIdLoader<T, ID> existingLoader,
            EntityByIdLoader<T, ID> candidateLoader
    ) {
        if (!(candidateLoader instanceof NoOpEntityByIdLoader)) {
            return candidateLoader;
        }
        return existingLoader == null ? candidateLoader : existingLoader;
    }

    private <T> EntityQueryLoader<T> selectQueryLoader(
            EntityQueryLoader<T> existingLoader,
            EntityQueryLoader<T> candidateLoader
    ) {
        if (!(candidateLoader instanceof NoOpEntityQueryLoader)) {
            return candidateLoader;
        }
        return existingLoader == null ? candidateLoader : existingLoader;
    }

    @Override
    public synchronized Optional<EntityBinding<?, ?>> find(String entityName) {
        return Optional.ofNullable(bindings.get(entityName));
    }

    @Override
    public synchronized Optional<EntityProjectionBinding<?, ?, ?>> findProjection(String entityName, String projectionName) {
        Map<String, EntityProjectionBinding<?, ?, ?>> entityProjections = projectionBindings.get(entityName);
        if (entityProjections == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entityProjections.get(projectionName));
    }

    @Override
    public synchronized Collection<EntityProjectionBinding<?, ?, ?>> projections(String entityName) {
        Map<String, EntityProjectionBinding<?, ?, ?>> entityProjections = projectionBindings.get(entityName);
        if (entityProjections == null || entityProjections.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.List.copyOf(entityProjections.values());
    }

    @Override
    public synchronized Collection<EntityBinding<?, ?>> all() {
        return java.util.List.copyOf(bindings.values());
    }
}
