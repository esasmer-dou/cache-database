package com.reactor.cachedb.redis;

final class RedisTestEndpoint {
    static String uri() {
        return "redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":"
                + Integer.getInteger("cachedb.snapshot.redis.port", 16383);
    }
    private RedisTestEndpoint() {}
}
