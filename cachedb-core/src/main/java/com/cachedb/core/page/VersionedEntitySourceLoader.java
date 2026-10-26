package com.reactor.cachedb.core.page;

import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;
import java.util.Optional;

public interface VersionedEntitySourceLoader<T, ID>
        extends EntityByIdLoader<T, ID>, EntityPageLoader<T>, EntityQueryLoader<T> {

    Optional<VersionedEntity<T>> loadVersionedById(ID id);

    /** A JDBC implementation can override this with a narrow ID/version probe. */
    default java.util.OptionalLong loadVersionById(ID id) {
        var row = loadVersionedById(id);
        return row.isEmpty() ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(row.get().version());
    }

    List<VersionedEntity<T>> loadVersionedPage(PageWindow pageWindow);

    List<VersionedEntity<T>> loadVersionedQuery(QuerySpec querySpec);

    @Override
    default Optional<T> load(ID id) {
        return loadVersionedById(id).map(VersionedEntity::entity);
    }

    @Override
    default List<T> load(PageWindow pageWindow) {
        return loadVersionedPage(pageWindow).stream().map(VersionedEntity::entity).toList();
    }

    @Override
    default List<T> load(QuerySpec querySpec) {
        return loadVersionedQuery(querySpec).stream().map(VersionedEntity::entity).toList();
    }
}
