package com.reactor.cachedb.redis;

import java.util.Objects;

/** Re-encryption-only transport seam; existing native wire callers remain compatible. */
@FunctionalInterface
public interface RedisReencryptionTransport {
    Session connect();

    interface Session extends AutoCloseable {
        BoundedRedisWire.Reply call(BoundedRedisWire.Limits limits, String... arguments);
        @Override void close();
    }

    static RedisReencryptionTransport borrowed(BoundedRedisWire wire) {
        Objects.requireNonNull(wire);
        return () -> {
            var session = wire.connect();
            return new Session() {
                public BoundedRedisWire.Reply call(BoundedRedisWire.Limits limits, String... arguments) {
                    return session.call(limits, arguments);
                }
                public void close() { session.close(); }
            };
        };
    }
}
