package com.reactor.cachedb.core.protection;

import java.util.Objects;

/**
 * Additive typed carrier for existing operation/queue/receipt values.
 * Does not change their record components or hide generation in trace/business metadata.
 */
public final class TenantGenerationEnvelope<T> {
    private final T payload;
    private final TenantPurgeProtocol.Generation generation;
    public TenantGenerationEnvelope(T payload,TenantPurgeProtocol.Generation generation) {
        this.payload=Objects.requireNonNull(payload);
        this.generation=Objects.requireNonNull(generation);
    }
    public T payload(){return payload;}
    public TenantPurgeProtocol.Generation generation(){return generation;}
    @Override public String toString(){return "TenantGenerationEnvelope[redacted]";}
}
