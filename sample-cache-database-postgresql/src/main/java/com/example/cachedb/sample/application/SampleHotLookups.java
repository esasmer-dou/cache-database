package com.example.cachedb.sample.application;

import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.repository.HotLookupStatus;

public final class SampleHotLookups {

    private SampleHotLookups() {
    }

    public static <T> T require(String entityName, Object id, HotLookup<T> lookup) {
        return lookup.orElseThrow(status -> status == HotLookupStatus.TOMBSTONED
                ? new SampleEntityNotFoundException(entityName, id)
                : new SampleHotDataUnavailableException(entityName, id, status));
    }
}
