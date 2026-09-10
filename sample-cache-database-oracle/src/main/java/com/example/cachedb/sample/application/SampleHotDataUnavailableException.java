package com.example.cachedb.sample.application;

import com.reactor.cachedb.core.repository.HotLookupStatus;

public final class SampleHotDataUnavailableException extends RuntimeException {

    private final HotLookupStatus lookupStatus;

    public SampleHotDataUnavailableException(String entityName, Object id, HotLookupStatus lookupStatus) {
        super(entityName + " " + id + " is not available on the declared Redis route (" + lookupStatus
                + "). Warm the route or use an explicit durable-source endpoint.");
        this.lookupStatus = lookupStatus;
    }

    public HotLookupStatus lookupStatus() {
        return lookupStatus;
    }
}
