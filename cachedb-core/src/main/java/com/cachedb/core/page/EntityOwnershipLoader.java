package com.reactor.cachedb.core.page;

import java.util.Collection;
import java.util.Map;

/** Trusted complete ownership lookup, including soft-deleted rows. Not a paginated entity query. */
public interface EntityOwnershipLoader<ID> {
    /** Maximum 500 requested globally unique identities; missing keys mean no SQL row exists. */
    Map<ID, String> loadTenantOwners(Collection<ID> ids);
}
