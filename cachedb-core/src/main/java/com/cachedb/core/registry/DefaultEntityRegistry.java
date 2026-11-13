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
    private final java.util.concurrent.locks.ReentrantLock registryLock = new java.util.concurrent.locks.ReentrantLock();

    private final ResourceLimits resourceLimits;
    private final com.reactor.cachedb.core.crypto.CacheKeyProvider keyProvider;
    private final Map<String, EntityBinding<?, ?>> bindings = new LinkedHashMap<>();
    private final Map<String,EntityBinding<?,?>> protectedBindings=new LinkedHashMap<>();
    @Override public Collection<EntityBinding<?,?>> tenantProtectedBindings(){
        registryLock.lock();
        try{return protectedBindings.isEmpty()?java.util.List.of():java.util.List.copyOf(protectedBindings.values());}
        finally{registryLock.unlock();}
    }
    private void protectionBinding(EntityBinding<?,?> binding){
        if(binding.metadata().tenantPurgeEnabled()){
            if(!protectedBindings.containsKey(binding.metadata().entityName())&&protectedBindings.size()>=10000)
                throw new IllegalStateException("Protection registry exceeds bounded validation");
            protectedBindings.put(binding.metadata().entityName(),binding);
        }else protectedBindings.remove(binding.metadata().entityName());
    }
    private final Map<String, Map<String, EntityProjectionBinding<?, ?, ?>>> projectionBindings = new LinkedHashMap<>();

    public DefaultEntityRegistry(ResourceLimits resourceLimits) {
        this(resourceLimits, null);
    }

    public DefaultEntityRegistry(ResourceLimits resourceLimits, com.reactor.cachedb.core.crypto.CacheKeyProvider keyProvider) {
        this.resourceLimits = java.util.Objects.requireNonNull(resourceLimits);
        this.keyProvider = keyProvider;
    }

    @Override
    public <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader
    ) {
        return EntityRegistry.super.register(metadata, codec, cachePolicy, relationBatchLoader, pageLoader);
    }

    @Override
    public <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader,
            EntityByIdLoader<T, ID> byIdLoader,
            EntityQueryLoader<T> queryLoader
    ) {
        codec = com.reactor.cachedb.core.crypto.EncryptedStorageChecks.bind(metadata, codec, keyProvider);
        registryLock.lock();
        try {
            EntityBinding<?, ?> existing = bindings.get(metadata.entityName());
            int threshold = cachePolicy == null ? metadata.compressionThresholdBytes()
                    : Math.max(metadata.compressionThresholdBytes(), cachePolicy.compressionThresholdBytes());
            if (codec != null) codec = com.reactor.cachedb.core.codec.PayloadEntityCodec.wrap(codec, metadata, threshold);
            RelationBatchLoader<T> safeLoader = relationBatchLoader == null ? new NoOpRelationBatchLoader<>() : relationBatchLoader;
            EntityPageLoader<T> safePageLoader = pageLoader == null ? new NoOpEntityPageLoader<>() : pageLoader;
            EntityByIdLoader<T, ID> safeByIdLoader = byIdLoader == null ? new NoOpEntityByIdLoader<>() : byIdLoader;
            EntityQueryLoader<T> safeQueryLoader = queryLoader == null ? new NoOpEntityQueryLoader<>() : queryLoader;
            if (existing != null) {
                @SuppressWarnings("unchecked")
                EntityBinding<T, ID> casted = (EntityBinding<T, ID>) existing;
                if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata)
                        || com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(casted.metadata()))
                    com.reactor.cachedb.core.crypto.EncryptedStorageChecks.sameMapping(metadata, casted.metadata());
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
                protectionBinding(updated);
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
            protectionBinding(binding);
            bindings.put(metadata.entityName(), binding);
            return binding;
        } finally {
            registryLock.unlock();
        }
    }

    @Override
    public <T, ID, P> EntityProjectionBinding<T, P, ID> registerProjection(
            EntityMetadata<T, ID> metadata,
            EntityProjection<T, P, ID> projection
    ) {
        registryLock.lock();
        try {
            var registered = bindings.get(metadata.entityName());
            if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata)
                    || registered != null && com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(registered.metadata()))
                throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.unbound();
            Map<String, EntityProjectionBinding<?, ?, ?>> entityProjections = projectionBindings.computeIfAbsent(
                    metadata.entityName(),
                    key -> new LinkedHashMap<>()
            );
            EntityProjectionBinding<T, P, ID> binding = new EntityProjectionBinding<>(metadata.entityName(), projection);
            entityProjections.put(projection.name(), binding);
            return binding;
        } finally {
            registryLock.unlock();
        }
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
    public Optional<EntityBinding<?, ?>> find(String entityName) {
        registryLock.lock();
        try {
            return Optional.ofNullable(bindings.get(entityName));
        } finally {
            registryLock.unlock();
        }
    }

    @Override
    public Optional<EntityProjectionBinding<?, ?, ?>> findProjection(String entityName, String projectionName) {
        registryLock.lock();
        try {
            Map<String, EntityProjectionBinding<?, ?, ?>> entityProjections = projectionBindings.get(entityName);
            if (entityProjections == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(entityProjections.get(projectionName));
        } finally {
            registryLock.unlock();
        }
    }

    @Override
    public Collection<EntityProjectionBinding<?, ?, ?>> projections(String entityName) {
        registryLock.lock();
        try {
            Map<String, EntityProjectionBinding<?, ?, ?>> entityProjections = projectionBindings.get(entityName);
            if (entityProjections == null || entityProjections.isEmpty()) {
                return java.util.List.of();
            }
            return java.util.List.copyOf(entityProjections.values());
        } finally {
            registryLock.unlock();
        }
    }

    @Override
    public Collection<EntityBinding<?, ?>> all() {
        registryLock.lock();
        try {
            return java.util.List.copyOf(bindings.values());
        } finally {
            registryLock.unlock();
        }
    }
}
