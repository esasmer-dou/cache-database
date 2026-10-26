package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.SourceRepository;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.guardrail.ReadShapeGuardrails;
import com.reactor.cachedb.core.page.EntityByIdLoader;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.page.NoOpEntityByIdLoader;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityQueryLoader;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.route.RouteCacheContext;

import java.util.List;
import java.util.Optional;

final class RegisteredSourceRepository<T, ID> implements SourceRepository<T, ID> {

    private final String entityName;
    private com.reactor.cachedb.core.model.EntityMetadata<T, ID> metadata;
    private final EntityByIdLoader<T, ID> byIdLoader;
    private final EntityPageLoader<T> pageLoader;
    private final EntityQueryLoader<T> queryLoader;

    RegisteredSourceRepository(
            String entityName,
            EntityByIdLoader<T, ID> byIdLoader,
            EntityPageLoader<T> pageLoader,
            EntityQueryLoader<T> queryLoader
    ) {
        this.entityName = entityName;
        this.byIdLoader = byIdLoader;
        this.pageLoader = pageLoader;
        this.queryLoader = queryLoader;
    }

    RegisteredSourceRepository(com.reactor.cachedb.core.model.EntityMetadata<T, ID> metadata,
            EntityByIdLoader<T, ID> byIdLoader, EntityPageLoader<T> pageLoader, EntityQueryLoader<T> queryLoader) {
        this(metadata.entityName(), byIdLoader, pageLoader, queryLoader); this.metadata = metadata;
    }

    @Override
    public Optional<T> findById(ID id) {
        if (metadata != null) com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
        ReadShapeGuardrails.validateRouteReadSize(RouteCacheContext.currentContract(), "Source by-id read", 1);
        if (byIdLoader instanceof NoOpEntityByIdLoader<?, ?>) {
            throw missingLoader("by-id");
        }
        Optional<T> result = Optional.ofNullable(byIdLoader.load(id)).orElse(Optional.empty());
        if (metadata != null) result.ifPresent(entity -> com.reactor.cachedb.core.tenant.TenantAccess.verify(metadata, entity));
        return result;
    }

    @Override
    public List<T> findPage(PageWindow pageWindow) {
        if (metadata != null && com.reactor.cachedb.core.tenant.TenantAccess.active(metadata) != null)
            return query(QuerySpec.allOf().offsetBy(pageWindow.offset()).limitTo(pageWindow.pageSize()));
        int requestedRows = pageWindow == null ? 0 : pageWindow.pageSize();
        ReadShapeGuardrails.validateRouteReadSize(
                RouteCacheContext.currentContract(), "Source page read", requestedRows
        );
        if (pageLoader instanceof NoOpEntityPageLoader<?>) {
            throw missingLoader("page");
        }
        List<T> loaded = pageLoader.load(pageWindow);
        return loaded == null ? List.of() : List.copyOf(loaded);
    }

    @Override
    public List<T> query(QuerySpec querySpec) {
        if (metadata != null) querySpec = com.reactor.cachedb.core.tenant.TenantAccess.scoped(metadata, querySpec);
        int requestedRows = querySpec == null ? 0 : querySpec.limit();
        ReadShapeGuardrails.validateRouteReadSize(
                RouteCacheContext.currentContract(), "Source query", requestedRows
        );
        if (queryLoader instanceof NoOpEntityQueryLoader<?>) {
            throw missingLoader("query");
        }
        List<T> loaded = queryLoader.load(querySpec);
        if (loaded != null && metadata != null) for (T entity : loaded) com.reactor.cachedb.core.tenant.TenantAccess.verify(metadata, entity);
        return loaded == null ? List.of() : List.copyOf(loaded);
    }

    private IllegalStateException missingLoader(String shape) {
        return new IllegalStateException(
                "No durable source " + shape + " loader is registered for " + entityName
        );
    }
}
