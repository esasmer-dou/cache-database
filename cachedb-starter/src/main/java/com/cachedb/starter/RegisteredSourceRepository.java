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

    @Override
    public Optional<T> findById(ID id) {
        ReadShapeGuardrails.validateRouteReadSize(RouteCacheContext.currentContract(), "Source by-id read", 1);
        if (byIdLoader instanceof NoOpEntityByIdLoader<?, ?>) {
            throw missingLoader("by-id");
        }
        return Optional.ofNullable(byIdLoader.load(id)).orElse(Optional.empty());
    }

    @Override
    public List<T> findPage(PageWindow pageWindow) {
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
        int requestedRows = querySpec == null ? 0 : querySpec.limit();
        ReadShapeGuardrails.validateRouteReadSize(
                RouteCacheContext.currentContract(), "Source query", requestedRows
        );
        if (queryLoader instanceof NoOpEntityQueryLoader<?>) {
            throw missingLoader("query");
        }
        List<T> loaded = queryLoader.load(querySpec);
        return loaded == null ? List.of() : List.copyOf(loaded);
    }

    private IllegalStateException missingLoader(String shape) {
        return new IllegalStateException(
                "No durable source " + shape + " loader is registered for " + entityName
        );
    }
}
