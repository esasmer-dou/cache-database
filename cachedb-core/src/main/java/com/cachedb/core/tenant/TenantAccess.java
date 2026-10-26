package com.reactor.cachedb.core.tenant;

import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;

/** Shared predicate/read guards; Redis additionally checks ownership during atomic mutation. */
public final class TenantAccess {
    private TenantAccess() {}
    public static String active(EntityMetadata<?, ?> metadata) {
        if (metadata.tenantFieldName() == null) return null;
        String tenant = CacheTenantContext.currentTenant().orElse(null);
        if (tenant == null && !CacheTenantContext.isAdministrative())
            throw new CacheTenantAccessDeniedException(metadata.entityName());
        return tenant;
    }
    public static <T> void verify(EntityMetadata<T, ?> metadata, T entity) {
        String tenant = active(metadata);
        if (tenant != null && !tenant.equals(metadata.extractTenantId(entity)))
            throw new CacheTenantAccessDeniedException(metadata.entityName());
    }
    public static QuerySpec scoped(EntityMetadata<?, ?> metadata, QuerySpec query) {
        String tenant = active(metadata);
        if (tenant == null) return query;
        if (query == null) query = QuerySpec.allOf();
        for (QueryFilter filter : query.filters()) if (filter.column().equals(metadata.tenantFieldName())
                && (filter.operator() != QueryOperator.EQ || !tenant.equals(filter.value())))
            throw new CacheTenantAccessDeniedException(metadata.entityName());
        return query.and(QueryFilter.eq(metadata.tenantFieldName(), tenant));
    }
}
