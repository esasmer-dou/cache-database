package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.codec.Utf8Length;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.csc.CacheConnection;
import redis.clients.jedis.util.Pool;

/**
 * One reserved connection from the actual standalone runtime pool. No new client, endpoint,
 * credentials, socket factory, executor or pool. Reply framing is capped inside Redis BEFORE
 * Jedis allocates the outer bulk response; the existing strict bounded RESP decoder follows.
 *
 * The at-most-2000ms SO timeout bounds each blocking socket read, not total command time,
 * writes, or a trickled reply. The 1000ms pool wait does not bound a custom factory's creation
 * or activation. The runner's elapsed budget is checked BETWEEN commands, not inside I/O.
 * Unlike BoundedRedisWire this adapter has no absolute wall-call deadline/abort timer.
 * close() only stops admission: it neither closes an active reservation nor certifies drain.
 * A sent command can finish after close, including server-side writes; no automatic retry
 * is performed. The database-owned job rejects a result observed after database closure.
 */
public final class RedisRuntimeReencryptionTransport implements RedisReencryptionTransport, AutoCloseable {
    private final Pool<Connection> pool;
    private final Semaphore admission = new Semaphore(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private static final String ENCODE = """
        local parts,bytes,nodes={},0,0
        local function append(s)
          bytes=bytes+#s
          if bytes>FRAME then error('CDB_REENCRYPT_LIMIT') end
          parts[#parts+1]=s
        end
        local function encode(v,depth)
          nodes=nodes+1
          if nodes>NODES or depth>32 then error('CDB_REENCRYPT_LIMIT') end
          if v==false or v==nil then append('$-1\\r\\n')
          elseif type(v)=='string' then
            if #v>BULK then error('CDB_REENCRYPT_LIMIT') end
            append('$'..#v..'\\r\\n'); append(v); append('\\r\\n')
          elseif type(v)=='number' then
            if v~=math.floor(v) or math.abs(v)>9007199254740991 then error('CDB_REENCRYPT_PROTOCOL') end
            append(':'..string.format('%.0f',v)..'\\r\\n')
          elseif type(v)=='table' then
            if v.err then error('CDB_REENCRYPT_PROTOCOL') end
            if v.ok then
              if #v.ok>64 then error('CDB_REENCRYPT_LIMIT') end
              append('+'..v.ok..'\\r\\n')
            else
              if #v>NODES-nodes then error('CDB_REENCRYPT_LIMIT') end
              append('*'..#v..'\\r\\n')
              for i=1,#v do encode(v[i],depth+1) end
            end
          else error('CDB_REENCRYPT_PROTOCOL') end
        end
        encode(run(),0)
        return table.concat(parts)
        """;

    public RedisRuntimeReencryptionTransport(RedisClientOperations runtime) {
        if (!(runtime instanceof JedisPooledOperations actual) || runtime.clustered()
                || actual.pooledClient().getPool().getMaxTotal() < 1)
            throw failure(BoundedRedisWire.Code.PROTOCOL);
        pool = actual.pooledClient().getPool();
    }

    @Override public Session connect() {
        if (closed.get()) throw failure(BoundedRedisWire.Code.IO_FAILURE);
        if (!admission.tryAcquire()) throw failure(BoundedRedisWire.Code.BUSY);
        Connection connection = null;
        try {
            connection = pool.borrowObject(Duration.ofMillis(1000));
            connection.setHandlingPool(null);
            if (closed.get() || connection.isBroken() || !connection.isConnected()
                    || connection instanceof CacheConnection
                    || connection.getRedisProtocol() != null && connection.getRedisProtocol() != RedisProtocol.RESP2)
                throw failure(BoundedRedisWire.Code.PROTOCOL);
            return new Reserved(connection);
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            if (connection != null) {
                connection.setBroken();
                try { pool.returnBrokenResource(connection); } catch (RuntimeException ignored) { }
            }
            admission.release();
            throw failure(BoundedRedisWire.Code.IO_FAILURE);
        }
    }

    private final class Reserved implements Session {
        private final Connection connection;
        private final int originalTimeout;
        private final Thread owner = Thread.currentThread();
        private boolean released, failed, pending;
        Reserved(Connection connection) {
            this.connection = connection;
            originalTimeout = connection.getSoTimeout();
        }

        public BoundedRedisWire.Reply call(BoundedRedisWire.Limits limits, String... command) {
            if (owner != Thread.currentThread() || released || closed.get())
                throw failure(BoundedRedisWire.Code.IO_FAILURE);
            if (command == null || command.length < 1 || command.length > 8192)
                throw failure(BoundedRedisWire.Code.INPUT_LIMIT);
            String body;
            int firstArgument, numberOfKeys;
            if ("EVAL".equals(command[0])) {
                if (command.length < 3) throw failure(BoundedRedisWire.Code.PROTOCOL);
                try { numberOfKeys = Integer.parseInt(command[2]); }
                catch (NumberFormatException invalid) { throw failure(BoundedRedisWire.Code.PROTOCOL); }
                if (numberOfKeys < 0 || numberOfKeys > 4096 || numberOfKeys > command.length - 3)
                    throw failure(BoundedRedisWire.Code.INPUT_LIMIT);
                body = command[1]; firstArgument = 3;
            } else {
                numberOfKeys = 0; firstArgument = 1;
                body = switch (command[0]) {
                    // Only the role is consumed by this kernel. INFO supplies the actual primary role.
                    case "ROLE" -> "local s=redis.call('INFO','REPLICATION'); local r=string.match(s,'role:([a-z]+)'); "
                            + "if not r then error('CDB_REENCRYPT_PROTOCOL') end; return {r}";
                    case "INFO" -> {
                        if (command.length != 2 || !"SERVER".equals(command[1]))
                            throw failure(BoundedRedisWire.Code.PROTOCOL);
                        yield "return redis.call('INFO',unpack(ARGV))";
                    }
                    case "SCAN" -> "return redis.call('SCAN',unpack(ARGV))";
                    default -> throw failure(BoundedRedisWire.Code.PROTOCOL);
                };
            }
            String program = "local FRAME,BULK,NODES=" + limits.frameBytes() + "," + limits.bulkBytes()
                    + "," + limits.nodes() + "\nlocal function run()\n" + body + "\nend\n" + ENCODE;
            long bytes = Utf8Length.of(program) + 128;
            for (int i = firstArgument; i < command.length; i++) {
                if (command[i] == null || command[i].length() > limits.commandBytes())
                    throw failure(BoundedRedisWire.Code.INPUT_LIMIT);
                bytes += Utf8Length.of(command[i]) + 32;
                if (bytes > limits.commandBytes()) throw failure(BoundedRedisWire.Code.INPUT_LIMIT);
            }
            if (bytes > limits.commandBytes()) throw failure(BoundedRedisWire.Code.INPUT_LIMIT);
            var args = new CommandArguments(Command.EVAL).add(program.getBytes(StandardCharsets.UTF_8))
                    .add(numberOfKeys);
            for (int i = firstArgument; i < command.length; i++) args.add(command[i].getBytes(StandardCharsets.UTF_8));
            try {
                if (Thread.currentThread().isInterrupted() || connection.isBroken() || !connection.isConnected())
                    throw failure(BoundedRedisWire.Code.IO_FAILURE);
                connection.setSoTimeout(originalTimeout > 0 ? Math.min(2000, originalTimeout) : 2000);
                pending = true;
                connection.sendCommand(args);
                Object raw = connection.getOne();
                if (!(raw instanceof byte[] frame) || frame.length > limits.frameBytes())
                    throw failure(BoundedRedisWire.Code.INPUT_LIMIT);
                var reply = BoundedRedisWire.decode(frame, limits);
                pending = false;
                return reply;
            } catch (RuntimeException unsafe) {
                failed = true;
                if (unsafe instanceof BoundedRedisWire.Failure safe) throw safe;
                String message = unsafe.getMessage();
                throw failure(message != null && message.contains("CDB_REENCRYPT_LIMIT")
                        ? BoundedRedisWire.Code.INPUT_LIMIT : BoundedRedisWire.Code.IO_FAILURE);
            }
        }

        public void close() {
            if (owner != Thread.currentThread()) throw failure(BoundedRedisWire.Code.PROTOCOL);
            if (released) return;
            released = true;
            // Also discard on Error during a partial send/read, not just caught RuntimeException.
            boolean broken = failed || pending || connection.isBroken();
            try {
                if (!broken) {
                    try { connection.setSoTimeout(originalTimeout); }
                    catch (RuntimeException unsafe) { broken = true; }
                }
                if (broken) { connection.setBroken(); pool.returnBrokenResource(connection); }
                else pool.returnResource(connection);
            } catch (RuntimeException unsafe) { throw failure(BoundedRedisWire.Code.IO_FAILURE); }
            finally { admission.release(); }
        }
    }

    /** Admission-only, not abort or drain. The physical owner alone returns its connection/permit. */
    @Override public void close() { closed.set(true); }
    private static BoundedRedisWire.Failure failure(BoundedRedisWire.Code code) { return new BoundedRedisWire.Failure(code); }
}
