package com.reactor.cachedb.core.change;

public interface ExternalChangeHydrationRepository<T, ID> {
    T hydrateExternalUpsert(T entity, long version);

    void hydrateExternalDelete(ID id, long version);
}
