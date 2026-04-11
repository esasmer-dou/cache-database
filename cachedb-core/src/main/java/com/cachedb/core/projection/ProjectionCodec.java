package com.reactor.cachedb.core.projection;

public interface ProjectionCodec<P> {
    String toRedisValue(P projection);
    P fromRedisValue(String encoded);
}
