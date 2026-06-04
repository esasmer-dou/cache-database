package com.reactor.cachedb.core.cache;

import java.util.Map;

@FunctionalInterface
public interface CacheAdmissionPredicate {
    boolean admit(Map<String, Object> columns);
}
