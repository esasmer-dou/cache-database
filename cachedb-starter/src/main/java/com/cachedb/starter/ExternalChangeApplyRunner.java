package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.change.ExternalChangeApplyException;
import com.reactor.cachedb.core.change.ExternalChangeApplyMode;
import com.reactor.cachedb.core.change.ExternalChangeApplyResult;
import com.reactor.cachedb.core.change.ExternalChangeEvent;
import com.reactor.cachedb.core.change.ExternalChangeHydrationRepository;
import com.reactor.cachedb.core.change.ExternalChangeSink;
import com.reactor.cachedb.core.change.ExternalChangeType;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ExternalChangeApplyRunner implements ExternalChangeSink {

    private final CacheSession cacheSession;
    private final EntityRegistry entityRegistry;
    private final ExternalChangeApplyMode mode;
    private final boolean ignoreUnknownEntities;
    private final Map<String, ExternalChangeEntityHandler> handlers;

    private ExternalChangeApplyRunner(Builder builder) {
        this.cacheSession = Objects.requireNonNull(builder.cacheSession, "cacheSession");
        this.entityRegistry = Objects.requireNonNull(builder.entityRegistry, "entityRegistry");
        this.mode = builder.mode;
        this.ignoreUnknownEntities = builder.ignoreUnknownEntities;
        this.handlers = Map.copyOf(builder.handlers);
    }

    public static Builder builder(CacheSession cacheSession, EntityRegistry entityRegistry) {
        return new Builder(cacheSession, entityRegistry);
    }

    @Override
    public void accept(ExternalChangeEvent event) {
        ExternalChangeApplyResult result = apply(event);
        if (result.failed()) {
            throw new ExternalChangeApplyException(result);
        }
    }

    public ExternalChangeApplyResult apply(ExternalChangeEvent event) {
        Objects.requireNonNull(event, "event");
        ExternalChangeEntityHandler handler = handlers.get(event.entityName());
        if (handler != null) {
            return applyHandler(event, handler);
        }
        try {
            return entityRegistry.find(event.entityName())
                    .map(binding -> applyRegistered(event, binding))
                    .orElseGet(() -> unknownEntityResult(event));
        } catch (RuntimeException exception) {
            return ExternalChangeApplyResult.failed(
                    event,
                    event.id(),
                    mode,
                    "External change apply failed for entity " + event.entityName(),
                    exception
            );
        }
    }

    private ExternalChangeApplyResult applyHandler(ExternalChangeEvent event, ExternalChangeEntityHandler handler) {
        try {
            ExternalChangeApplyResult result = handler.apply(event);
            if (result == null) {
                return ExternalChangeApplyResult.failed(
                        event,
                        event.id(),
                        mode,
                        "External change handler returned null for entity " + event.entityName(),
                        null
                );
            }
            return result;
        } catch (RuntimeException exception) {
            return ExternalChangeApplyResult.failed(
                    event,
                    event.id(),
                    mode,
                    "External change handler failed for entity " + event.entityName(),
                    exception
            );
        }
    }

    private ExternalChangeApplyResult unknownEntityResult(ExternalChangeEvent event) {
        String detail = "No registered CacheDB entity found for external change surface: " + event.entityName();
        if (ignoreUnknownEntities) {
            return ExternalChangeApplyResult.ignored(event, event.id(), mode, detail);
        }
        return ExternalChangeApplyResult.failed(event, event.id(), mode, detail, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ExternalChangeApplyResult applyRegistered(ExternalChangeEvent event, EntityBinding<?, ?> binding) {
        return applyTyped(event, (EntityBinding) binding);
    }

    private <T, ID> ExternalChangeApplyResult applyTyped(ExternalChangeEvent event, EntityBinding<T, ID> binding) {
        EntityRepository<T, ID> repository = cacheSession.repository(binding);
        if (event.type() == ExternalChangeType.DELETE) {
            ID id = resolveId(event, binding);
            if (mode == ExternalChangeApplyMode.CACHE_AND_WRITE_BEHIND) {
                repository.deleteById(id);
                return ExternalChangeApplyResult.applied(event, id, mode, "DELETE applied through repository.deleteById");
            }
            externalRepository(event, repository).hydrateExternalDelete(id, event.version());
            return ExternalChangeApplyResult.applied(event, id, mode, "DELETE applied to Redis hot set without write-behind");
        }

        T entity = entityFromEvent(event, binding);
        ID id = binding.metadata().idAccessor().apply(entity);
        if (id == null) {
            return ExternalChangeApplyResult.failed(
                    event,
                    event.id(),
                    mode,
                    "External UPSERT payload did not resolve a non-null id for entity " + event.entityName(),
                    null
            );
        }
        if (mode == ExternalChangeApplyMode.CACHE_AND_WRITE_BEHIND) {
            repository.save(entity);
            return ExternalChangeApplyResult.applied(event, id, mode, "UPSERT applied through repository.save");
        }
        externalRepository(event, repository).hydrateExternalUpsert(entity, event.version());
        return ExternalChangeApplyResult.applied(event, id, mode, "UPSERT applied to Redis hot set without write-behind");
    }

    private <T, ID> ExternalChangeHydrationRepository<T, ID> externalRepository(
            ExternalChangeEvent event,
            EntityRepository<T, ID> repository
    ) {
        if (repository instanceof ExternalChangeHydrationRepository<?, ?> externalRepository) {
            @SuppressWarnings("unchecked")
            ExternalChangeHydrationRepository<T, ID> typedRepository =
                    (ExternalChangeHydrationRepository<T, ID>) externalRepository;
            return typedRepository;
        }
        throw new IllegalStateException(
                "Repository for " + event.entityName()
                        + " does not support cache-only external change hydration. "
                        + "Use the Redis repository implementation or register an explicit ExternalChangeEntityHandler."
        );
    }

    private <T, ID> T entityFromEvent(ExternalChangeEvent event, EntityBinding<T, ID> binding) {
        Map<String, Object> columns = columnsWithId(event, binding.metadata());
        try {
            return binding.codec().fromColumns(columns);
        } catch (UnsupportedOperationException exception) {
            throw new IllegalArgumentException(
                    "Codec for " + event.entityName()
                            + " must implement EntityCodec.fromColumns for default external UPSERT apply. "
                            + "Use generated CacheBinding codecs or register an explicit ExternalChangeEntityHandler.",
                    exception
            );
        }
    }

    private <T, ID> ID resolveId(ExternalChangeEvent event, EntityBinding<T, ID> binding) {
        Map<String, Object> columns = columnsWithId(event, binding.metadata());
        try {
            T entity = binding.codec().fromColumns(columns);
            ID id = binding.metadata().idAccessor().apply(entity);
            if (id != null) {
                return id;
            }
        } catch (UnsupportedOperationException exception) {
            throw new IllegalArgumentException(
                    "Codec for " + event.entityName()
                            + " must implement EntityCodec.fromColumns for default external DELETE id resolution. "
                            + "Register an explicit ExternalChangeEntityHandler when the outbox only carries partial ids.",
                    exception
            );
        }
        throw new IllegalArgumentException("External DELETE event did not resolve a non-null id for entity " + event.entityName());
    }

    private Map<String, Object> columnsWithId(ExternalChangeEvent event, EntityMetadata<?, ?> metadata) {
        LinkedHashMap<String, Object> columns = new LinkedHashMap<>(event.columns());
        if (event.id() != null && !containsKeyIgnoreCase(columns, metadata.idColumn())) {
            columns.put(metadata.idColumn(), event.id());
        }
        return columns;
    }

    private boolean containsKeyIgnoreCase(Map<String, Object> columns, String key) {
        if (columns.containsKey(key)) {
            return true;
        }
        for (String candidate : columns.keySet()) {
            if (candidate != null && candidate.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    public interface ExternalChangeEntityHandler {
        ExternalChangeApplyResult apply(ExternalChangeEvent event);
    }

    public static final class Builder {
        private final CacheSession cacheSession;
        private final EntityRegistry entityRegistry;
        private ExternalChangeApplyMode mode = ExternalChangeApplyMode.CACHE_ONLY;
        private boolean ignoreUnknownEntities;
        private final Map<String, ExternalChangeEntityHandler> handlers = new LinkedHashMap<>();

        private Builder(CacheSession cacheSession, EntityRegistry entityRegistry) {
            this.cacheSession = cacheSession;
            this.entityRegistry = entityRegistry;
        }

        public Builder mode(ExternalChangeApplyMode mode) {
            this.mode = mode == null ? ExternalChangeApplyMode.CACHE_ONLY : mode;
            return this;
        }

        public Builder ignoreUnknownEntities(boolean ignoreUnknownEntities) {
            this.ignoreUnknownEntities = ignoreUnknownEntities;
            return this;
        }

        public Builder handler(String entityName, ExternalChangeEntityHandler handler) {
            if (entityName == null || entityName.isBlank()) {
                throw new IllegalArgumentException("entityName must not be blank");
            }
            handlers.put(entityName.trim(), Objects.requireNonNull(handler, "handler"));
            return this;
        }

        public ExternalChangeApplyRunner build() {
            return new ExternalChangeApplyRunner(this);
        }
    }
}
