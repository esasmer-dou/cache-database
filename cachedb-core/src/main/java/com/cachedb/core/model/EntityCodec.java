package com.reactor.cachedb.core.model;

import java.util.Map;

public interface EntityCodec<T> {
    String toRedisValue(T entity);
    T fromRedisValue(String encoded);
    Map<String, Object> toColumns(T entity);
}
