package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.model.EntityMetadata;

/** Safety quarantine: an unintegrated legacy path must never silently act on protected metadata. */
public final class TenantProtectionGate {
    private TenantProtectionGate(){}
    public static void requireLegacy(EntityMetadata<?,?> metadata) {
        if(metadata.tenantPurgeEnabled())throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
    }
}
