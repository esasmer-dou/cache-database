package com.reactor.cachedb.core.projection;

public interface ProjectionValueCodec<V> {
    String encode(V value);

    V decode(String value);
}
