package com.reactor.cachedb.core.codec;

import java.io.IOException;

/** Explicit single-value wire contract for generated distributed-job adapters. */
public interface CacheValueCodec<V> {
    String encodeValue(V value) throws IOException;
    V decodeValue(String encoded) throws IOException;
}
