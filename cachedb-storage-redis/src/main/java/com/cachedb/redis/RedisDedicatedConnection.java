package com.reactor.cachedb.redis;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.Protocol.Command;

/** An owned socket, never borrowed/returned through the application's connection pool. */
public final class RedisDedicatedConnection implements AutoCloseable {
    private final PooledObjectFactory<Connection> factory;
    private final PooledObject<Connection> owned;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RedisDedicatedConnection(PooledObjectFactory<Connection> factory, PooledObject<Connection> owned) {
        this.factory = factory; this.owned = owned;
    }
    static RedisDedicatedConnection open(PooledObjectFactory<Connection> factory, int timeout) {
        if (timeout < 50 || timeout > 2000) throw new IllegalArgumentException("Invalid dedicated I/O timeout");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Dedicated connection creation cancelled");
        RedisDedicatedConnection result = null;
        try {
            // Do not substitute getResource(): it would reserve/poison a shared pooled connection.
            result = new RedisDedicatedConnection(factory, factory.makeObject());
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException("Dedicated connection creation cancelled");
            Connection connection = result.connection();
            connection.setHandlingPool(null);
            connection.setSoTimeout(timeout);
            if (connection.getRedisProtocol() != null && connection.getRedisProtocol() != RedisProtocol.RESP2)
                throw new IllegalStateException("Tracking subscription requires RESP2");
            if (connection instanceof redis.clients.jedis.csc.CacheConnection)
                throw new IllegalStateException("Existing Jedis client-side caching cannot own tracking sockets");
            return result;
        } catch (Exception failure) {
            if (result != null) try { result.close(); }
            catch (RuntimeException cleanup) {
                cleanup.addSuppressed(failure);
                throw new CleanupFailure(cleanup);
            }
            throw new IllegalStateException("Dedicated Redis connection creation failed", failure);
        }
    }
    static final class CleanupFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        CleanupFailure(RuntimeException cause) { super("Dedicated Redis connection destruction was not confirmed", cause); }
    }
    public Connection connection() { return owned.getObject(); }
    /** A view of this owned connection; never use it concurrently with the connection's owner. */
    public Jedis jedis() { return new Jedis(connection()); }
    Object command(Command command, String... arguments) {
        connection().sendCommand(command, arguments);
        return connection().getOne();
    }
    // Jedis' flush is protected; a zero-response getMany flushes without reading S's input stream.
    void flush() { connection().getMany(0); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException primary = null;
        try { connection().close(); } catch (RuntimeException failure) { primary = failure; }
        try { factory.destroyObject(owned); }
        catch (Exception failure) {
            if (primary == null) primary = new IllegalStateException("Dedicated Redis connection cleanup failed", failure);
            else primary.addSuppressed(failure);
        }
        if (primary != null) throw primary;
    }
}
