package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Connection;
import redis.clients.jedis.exceptions.*;
import redis.clients.jedis.util.Pool;

class RedisStandaloneExecAclTest {
    private static final List<String> ENTITIES = List.of("entity:1", "entity:2");
    private static final List<String> TOMBS = List.of("tomb:1", "tomb:2");
    private static final List<String> META = List.of("meta:1", "meta:2");
    private static final List<String> OWNERS = List.of("owner:1", "owner:2");
    private static final String PREFIX = "EXECABORT Transaction discarded because of: ";

    @Test void realJedisGetManyRetainsWrappedExecErrorInItsAlignedSlot() {
        WireConnection connection = new WireConnection();
        var error = wrapped(denial("exec"));
        connection.reply(bytes("OK"), bytes("QUEUED"), error);
        List<Object> replies = connection.getMany(3);
        assertEquals(3, replies.size());
        assertArrayEquals(bytes("OK"), (byte[]) replies.get(0));
        assertArrayEquals(bytes("QUEUED"), (byte[]) replies.get(1));
        assertSame(error, replies.get(2));
        assertEquals(3, connection.wireReads);
    }

    @Test void wrappedExecRejectionFallsBackAfterDrainingAndDiscardingTenantAndGlobalCount() {
        for (boolean tenant : List.of(false, true)) {
            try (WirePool pool = new WirePool()) {
                var replies = new ArrayList<Object>();
                replies.add(bytes("OK")); replies.add(bytes("QUEUED")); replies.add(bytes("QUEUED"));
                if (tenant) { replies.add(bytes("QUEUED")); replies.add(bytes("QUEUED")); }
                replies.add(wrapped(denial("exec")));
                pool.connection.reply(replies.toArray());
                assertTrue(RedisStandaloneIndexSnapshot.aggregate(pool, ENTITIES, TOMBS, META,
                        tenant ? OWNERS : null, tenant ? "a" : null, null, true).isEmpty());
                discarded(pool);
                assertEquals(pool.connection.replies.size(), pool.connection.wireReads);
                assertEquals(pool.connection.wireReads, pool.readsAtDiscard);
                assertEquals(1, pool.connection.exchanges);
            }
        }
    }

    @Test void wrappedExecRejectionSupportsCountFieldNumericAndFacetAfterAcknowledgedMulti() {
        for (int operation = 0; operation < 3; operation++) {
            try (WirePool pool = new WirePool()) {
                owned(pool);
                pool.connection.reply(1L, 1L, bytes("OK"));
                // EXEC rechecks four admission commands and both field lengths before HGET.
                pool.connection.reply(bytes("QUEUED"), bytes("QUEUED"), bytes("QUEUED"), bytes("QUEUED"));
                pool.connection.reply(bytes("QUEUED"), bytes("QUEUED"));
                pool.connection.reply(bytes("QUEUED"), bytes("QUEUED"), wrapped(denial("exec")));
                if (operation == 2) assertTrue(RedisStandaloneIndexSnapshot.facet(pool,
                        ENTITIES, TOMBS, META, OWNERS, "a", "group", null).isEmpty());
                else assertTrue(RedisStandaloneIndexSnapshot.aggregate(pool,
                        ENTITIES, TOMBS, META, OWNERS, "a", "amount", operation == 0).isEmpty());
                discarded(pool);
                assertEquals(5, pool.connection.exchanges);
                assertEquals(2, Collections.frequency(pool.connection.commands, "HGET"));
                assertEquals(pool.connection.replies.size(), pool.connection.wireReads);
                assertEquals(pool.connection.wireReads, pool.readsAtDiscard);
            }
        }
    }

    @Test void rejectedMultiAndWrappedExecAreBothVerifiedBeforeFallback() {
        try (WirePool pool = new WirePool()) {
            pool.connection.reply(denied("multi"), 2L, 0L, bytes("a"), bytes("a"), wrapped(denial("exec")));
            assertTrue(count(pool).isEmpty()); discarded(pool);
        }
    }

    @Test void queuedNativeCommandDenialAndWrappedExecAreBothVerifiedBeforeFallback() {
        try (WirePool pool = new WirePool()) {
            pool.connection.reply(bytes("OK"), bytes("QUEUED"), bytes("QUEUED"),
                    denied("getrange"), bytes("QUEUED"), wrapped(denial("exec")));
            assertTrue(count(pool).isEmpty()); discarded(pool);
        }
    }

    @Test void onlyExactExecCommandDenialCanBeUnwrapped() {
        for (String message : List.of(
                PREFIX + denial("watch"), PREFIX + denial("multi"), PREFIX + denial("getrange"),
                PREFIX + "NOAUTH Authentication required.", PREFIX + "WRONGPASS invalid credentials",
                PREFIX + "WRONGTYPE Operation against a key holding the wrong kind of value",
                PREFIX + "NOPERM No permissions to access a key",
                PREFIX + "NOPERM User test has no permissions to access the 'exec' key",
                PREFIX + "NOPERM ACLs rules changed between queue and EXEC",
                PREFIX + "NOPERM User  has no permissions to run the 'exec' command",
                PREFIX + denial("exec") + " trailing data", PREFIX + denial("exec") + "\r\n",
                "EXECABORT " + denial("exec"), "ERR " + PREFIX + denial("exec"),
                "EXECABORT Transaction discarded because of previous errors.", denial("exec"))) {
            try (WirePool pool = new WirePool()) {
                var error = new JedisDataException(message);
                queuedCount(pool, error);
                assertSame(error, assertThrows(JedisDataException.class, () -> count(pool)), message);
                discarded(pool);
            }
        }
    }

    @Test void wrappedExecMustBeExactDataExceptionWithoutCauseOrSuppressedFailure() {
        String message = PREFIX + denial("exec");
        var caused = new JedisDataException(message, new JedisConnectionException("IO"));
        var suppressed = new JedisDataException(message);
        suppressed.addSuppressed(new JedisConnectionException("IO"));
        var subclass = new JedisDataException(message) {};
        var accessControlSubclass = new JedisAccessControlException(message);
        for (JedisDataException error : List.of(caused, suppressed, subclass, accessControlSubclass)) {
            try (WirePool pool = new WirePool()) {
                queuedCount(pool, error);
                assertSame(error, assertThrows(JedisDataException.class, () -> count(pool)));
                discarded(pool);
            }
        }
    }

    @Test void wrappedExecCannotHideMalformedOrDeniedQueueCompanions() {
        for (Object companion : List.of(bytes("unexpected"), 1L,
                new JedisDataException("WRONGTYPE"),
                new JedisAccessControlException("NOAUTH Authentication required."),
                denied("exists"), denied("watch"))) {
            try (WirePool pool = new WirePool()) {
                pool.connection.reply(bytes("OK"), bytes("QUEUED"), companion,
                        bytes("QUEUED"), bytes("QUEUED"), wrapped(denial("exec")));
                RuntimeException failure = assertThrows(RuntimeException.class, () -> count(pool));
                if (companion instanceof JedisDataException) assertSame(companion, failure);
                else assertInstanceOf(IllegalStateException.class, failure);
                discarded(pool);
            }
        }
    }

    @Test void wrappedExecCannotHideUncertifiedProofAfterMultiDenial() {
        for (Object companion : List.of("not raw bytes", new JedisDataException("WRONGTYPE"),
                new JedisAccessControlException("NOAUTH Authentication required."))) {
            try (WirePool pool = new WirePool()) {
                pool.connection.reply(denied("multi"), 2L, 0L, bytes("a"), companion, wrapped(denial("exec")));
                RuntimeException failure = assertThrows(RuntimeException.class, () -> count(pool));
                if (companion instanceof JedisDataException) assertSame(companion, failure);
                else assertInstanceOf(IllegalStateException.class, failure);
                discarded(pool);
            }
        }
    }

    @Test void sharedMetadataReadDenialWinsOverWrappedExec() {
        for (int operation = 0; operation < 3; operation++) try (WirePool pool = new WirePool()) {
            owned(pool); pool.connection.reply(1L, 1L, bytes("OK"));
            var hgetDenied = denied("hget");
            pool.connection.reply(bytes("QUEUED"), bytes("QUEUED"), bytes("QUEUED"), bytes("QUEUED"));
            pool.connection.reply(bytes("QUEUED"), bytes("QUEUED"));
            pool.connection.reply(hgetDenied, bytes("QUEUED"), wrapped(denial("exec")));
            // Watched metadata ACL changes invalidate the native snapshot, never return a partial result.
            if (operation == 2) assertTrue(RedisStandaloneIndexSnapshot.facet(pool,
                    ENTITIES, TOMBS, META, OWNERS, "a", "group", null).isEmpty());
            else assertTrue(RedisStandaloneIndexSnapshot.aggregate(pool,
                    ENTITIES, TOMBS, META, OWNERS, "a", "amount", operation == 0).isEmpty());
            discarded(pool);
            assertEquals(5, pool.connection.exchanges);
            assertEquals(2, Collections.frequency(pool.connection.commands, "HGET"));
            assertEquals("EXEC", pool.connection.commands.get(pool.connection.commands.size() - 1));
            assertEquals(pool.connection.replies.size(), pool.connection.wireReads);
            assertEquals(pool.connection.wireReads, pool.readsAtDiscard);
        }
    }

    @Test void wrappedMessageInNonExecPositionCannotRequestFallback() {
        try (WirePool pool = new WirePool()) {
            var error = wrapped(denial("exec"));
            pool.connection.reply(bytes("OK"), bytes("QUEUED"), bytes("QUEUED"),
                    error, bytes("QUEUED"), wrapped(denial("exec")));
            assertSame(error, assertThrows(JedisDataException.class, () -> count(pool))); discarded(pool);
        }
    }

    @Test void nestedExecResultErrorsAreNotTopLevelCommandRejections() {
        try (WirePool pool = new WirePool()) {
            var error = wrapped(denial("exec"));
            queuedCount(pool, List.of(2L, 0L, error, bytes("a")));
            assertSame(error, assertThrows(JedisDataException.class, () -> count(pool)));
            assertEquals(1, pool.healthyReturns); assertEquals(0, pool.brokenReturns);
        }
    }

    @Test void thrownGetManyWrapperIsUnalignedAndNeverAnAlgorithmFallback() {
        try (WirePool pool = new WirePool()) {
            var error = wrapped(denial("exec"));
            pool.connection.unalignedFailure = error;
            assertSame(error, assertThrows(JedisDataException.class, () -> count(pool)));
            assertEquals(0, pool.connection.wireReads);
            discarded(pool);
        }
    }

    @Test void incompleteAlignedReplyListCannotUseWrappedExecAsCapabilityProof() {
        try (WirePool pool = new WirePool()) {
            queuedCount(pool, wrapped(denial("exec")));
            pool.connection.truncateReplies = true;
            assertThrows(IllegalStateException.class, () -> count(pool)); discarded(pool);
        }
    }

    @Test void wrappedExecCannotHideConnectionLossAfterReadOrFailedDiscard() {
        try (WirePool pool = new WirePool()) {
            queuedCount(pool, wrapped(denial("exec")));
            pool.connection.disconnectAfterExchange = true;
            assertThrows(JedisConnectionException.class, () -> count(pool)); discarded(pool);
        }
        try (WirePool pool = new WirePool()) {
            queuedCount(pool, wrapped(denial("exec")));
            pool.failDiscard = true;
            assertThrows(JedisConnectionException.class, () -> count(pool)); discarded(pool);
        }
    }

    private static Optional<RedisClientOperations.IndexAggregateBatch> count(WirePool pool) {
        return RedisStandaloneIndexSnapshot.aggregate(pool, ENTITIES, TOMBS, META, OWNERS, "a", null, true);
    }
    private static void owned(WirePool pool) {
        pool.connection.reply(bytes("OK"), 2L, 0L, bytes("a"), bytes("a"));
    }
    private static void queuedCount(WirePool pool, Object tail) {
        pool.connection.reply(bytes("OK"), bytes("QUEUED"), bytes("QUEUED"),
                bytes("QUEUED"), bytes("QUEUED"), tail);
    }
    private static String denial(String command) {
        return "NOPERM User test has no permissions to run the '" + command + "' command";
    }
    private static JedisAccessControlException denied(String command) {
        return new JedisAccessControlException(denial(command));
    }
    private static JedisDataException wrapped(String reason) { return new JedisDataException(PREFIX + reason); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void discarded(WirePool pool) {
        assertEquals(1, pool.brokenReturns); assertEquals(0, pool.healthyReturns);
        assertTrue(pool.connection.isBroken());
    }

    // Exercise the SDK's real getMany decoder loop, replacing only socket I/O.
    private static final class WireConnection extends Connection {
        final List<Object> replies = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        int wireReads, exchanges;
        boolean connected = true, disconnectAfterExchange, truncateReplies;
        JedisDataException unalignedFailure;
        void reply(Object... values) { Collections.addAll(replies, values); }
        @Override public boolean isConnected() { return connected; }
        @Override public void sendCommand(CommandArguments command) {
            commands.add(command.getCommand().toString());
        }
        @Override protected void flush() {}
        @Override protected Object readProtocolWithCheckingBroken() {
            Object reply = replies.get(wireReads++);
            if (reply instanceof JedisDataException error) throw error;
            return reply;
        }
        @Override public List<Object> getMany(int count) {
            exchanges++;
            if (unalignedFailure != null) throw unalignedFailure;
            List<Object> result = super.getMany(count);
            if (disconnectAfterExchange) connected = false;
            if (truncateReplies) result.remove(result.size() - 1);
            return result;
        }
    }

    private static final class WirePool extends Pool<Connection> {
        final WireConnection connection = new WireConnection();
        int healthyReturns, brokenReturns, readsAtDiscard;
        boolean failDiscard;
        WirePool() { super(new BasePooledObjectFactory<Connection>() {
            public Connection create() { throw new AssertionError("No network expected"); }
            public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
        }, config()); }
        private static GenericObjectPoolConfig<Connection> config() {
            var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false); return config;
        }
        @Override public Connection borrowObject(Duration wait) { return connection; }
        @Override public void returnResource(Connection value) { assertSame(connection, value); healthyReturns++; }
        @Override public void returnBrokenResource(Connection value) {
            assertSame(connection, value); brokenReturns++; readsAtDiscard = connection.wireReads;
            if (failDiscard) throw new JedisConnectionException("Injected discard failure");
        }
    }
}
