package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.tenant.TenantAccess;

final class RedisTenantWriteGuard {
    private RedisTenantWriteGuard() {}
    static String tenant(WriteOperation<?, ?> operation) {
        if (operation.metadata().tenantFieldName() == null) return null;
        String tenant = TenantAccess.active(operation.metadata());
        if (tenant != null || operation.type() != OperationType.UPSERT) return tenant;
        Object value = operation.columns().get(operation.metadata().tenantFieldName());
        if (!(value instanceof String owner) || owner.isBlank() || owner.length() > 256)
            throw new IllegalArgumentException("Tenant UPSERT requires an explicit valid owner");
        return owner;
    }
}
