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
import redis.clients.jedis.Connection;
import redis.clients.jedis.exceptions.*;
import redis.clients.jedis.util.Pool;

class RedisStandaloneIndexAclTest {
    private static final List<String> ENTITIES = List.of("entity:1", "entity:2");
    private static final List<String> TOMBS = List.of("tomb:1", "tomb:2");
    private static final List<String> META = List.of("meta:1", "meta:2");
    private static final List<String> OWNERS = List.of("owner:1", "owner:2");

    @Test void verifiedWatchRejectionReturnsAlgorithmUnavailableAfterDiscard() {
        try (TestPool pool = new TestPool()) {
            pool.connection.reply(List.of(denied("watch"), 2L, 0L, bytes("a"), bytes("a")));
            assertTrue(aggregate(pool, "amount").isEmpty());
            discarded(pool);
            assertFalse(pool.connection.names().contains("HSTRLEN"));
        }
    }

    @Test void rejectedCountMultiAcceptsOnlyItsExactDerivedExecError() {
        try (TestPool pool = new TestPool()) {
            pool.connection.reply(List.of(denied("multi"), 2L, 0L, bytes("a"), bytes("a"),
                    new JedisDataException("ERR EXEC without MULTI")));
            assertTrue(aggregate(pool, null).isEmpty()); discarded(pool);
        }
        try (TestPool pool = new TestPool()) {
            pool.connection.reply(List.of(denied("multi"), 2L, 0L, bytes("a"), bytes("a"), denied("exec")));
            assertTrue(aggregate(pool, null).isEmpty()); discarded(pool);
        }
    }

    @Test void rejectedMetadataMultiDoesNotSendHgetOrExec() {
        try (TestPool pool = new TestPool()) {
            owned(pool);
            pool.connection.reply(List.of(1L, 1L, denied("multi")));
            assertTrue(aggregate(pool, "amount").isEmpty()); discarded(pool);
            assertFalse(pool.connection.names().contains("HGET"));
            assertFalse(pool.connection.names().contains("EXEC"));
        }
    }

    @Test void getrangeDenialIsCommandAlignedForDirectAndQueuedReplies() {
        try (TestPool pool = new TestPool()) {
            pool.connection.reply(List.of(bytes("OK"), 2L, 0L, denied("getrange"), bytes("a")));
            assertTrue(aggregate(pool, "amount").isEmpty()); discarded(pool);
        }
        try (TestPool pool = new TestPool()) {
            pool.connection.reply(List.of(bytes("OK"), bytes("QUEUED"), bytes("QUEUED"),
                    denied("getrange"), bytes("QUEUED"), execAbort()));
            assertTrue(aggregate(pool, null).isEmpty()); discarded(pool);
        }
    }

    @Test void explicitExecRejectionDiscardsKnownTransactionBeforeFallbackSignal() {
        try (TestPool pool = new TestPool()) {
            owned(pool);
            pool.connection.reply(List.of(1L, 1L, bytes("OK")));
            // Keep each getMany response aligned with proof, lengths, then HGET/EXEC.
            pool.connection.reply(List.of(bytes("QUEUED"), bytes("QUEUED"), bytes("QUEUED"), bytes("QUEUED")));
            pool.connection.reply(List.of(bytes("QUEUED"), bytes("QUEUED")));
            pool.connection.reply(List.of(bytes("QUEUED"), bytes("QUEUED"), denied("exec")));
            assertTrue(aggregate(pool, "amount").isEmpty()); discarded(pool);
            assertEquals(List.of(5, 3, 4, 2, 3), pool.connection.requestedReplies);
        }
    }

    @Test void noauthWrongpassKeyDenialsAndNoncanonicalMessagesDoNotFallBack() {
        for (String message : List.of("NOAUTH Authentication required.", "WRONGPASS invalid username-password pair",
                "NOPERM No permissions to access a key", "NOPERM User test has no permissions to access the 'x' key",
                "NOPERM", "NOPERM WATCH", "NOPERM ACLs rules changed between queue and EXEC",
                "NOPERM User test has no permissions to run the 'multi' command")) {
            try (TestPool pool = new TestPool()) {
                var error = new JedisAccessControlException(message);
                pool.connection.reply(List.of(error, 2L, 0L, bytes("a"), bytes("a")));
                assertSame(error, assertThrows(JedisAccessControlException.class, () -> aggregate(pool, "amount")));
                discarded(pool);
            }
        }
    }

    @Test void genericDataExceptionWithPermissionTextIsNotWireVerified() {
        try (TestPool pool = new TestPool()) {
            var error = new JedisDataException(denied("watch").getMessage());
            pool.connection.reply(List.of(error, 2L, 0L, bytes("a"), bytes("a")));
            assertSame(error, assertThrows(JedisDataException.class, () -> aggregate(pool, "amount")));
            discarded(pool);
        }
    }

    @Test void wrappedOrSuppressedIoCannotMasqueradeAsVerifiedAclOrDerivedReply() {
        var wrapped = new JedisAccessControlException(denied("watch").getMessage(), new JedisConnectionException("IO"));
        var suppressed = denied("watch"); suppressed.addSuppressed(new JedisConnectionException("IO"));
        for (var error : List.of(wrapped, suppressed)) {
            try (TestPool pool = new TestPool()) {
                pool.connection.reply(List.of(error, 2L, 0L, bytes("a"), bytes("a")));
                assertSame(error, assertThrows(JedisAccessControlException.class, () -> aggregate(pool, "amount")));
                discarded(pool);
            }
        }
        try (TestPool pool = new TestPool()) {
            var error = new JedisDataException("ERR EXEC without MULTI", new JedisConnectionException("IO"));
            pool.connection.reply(List.of(denied("multi"), 2L, 0L, bytes("a"), bytes("a"), error));
            assertSame(error, assertThrows(JedisDataException.class, () -> aggregate(pool, null)));
            discarded(pool);
        }
    }

    @Test void accompanyingWrongtypeAndNoauthAreNotHiddenByWatchRejection() {
        for (JedisDataException error : List.of(new JedisDataException("WRONGTYPE"),
                new JedisAccessControlException("NOAUTH Authentication required."))) {
            try (TestPool pool = new TestPool()) {
                pool.connection.reply(List.of(denied("watch"), 2L, 0L, bytes("a"), error));
                assertSame(error, assertThrows(JedisDataException.class, () -> aggregate(pool, "amount")));
                discarded(pool);
            }
        }
    }

    @Test void malformedReplyAlongsidePermissionRejectionFailsClosed() {
        try (TestPool pool = new TestPool()) {
            pool.connection.reply(List.of(denied("watch"), 2L, 0L, "not raw bytes", bytes("a")));
            assertThrows(IllegalStateException.class, () -> aggregate(pool, "amount")); discarded(pool);
        }
        try (TestPool pool = new TestPool()) {
            pool.connection.reply(List.of(denied("watch")));
            assertThrows(IllegalStateException.class, () -> aggregate(pool, "amount")); discarded(pool);
        }
    }

    @Test void budgetOrWrongtypeInLengthPhaseWinsOverDeniedMulti() {
        for (Object size : List.of(RedisStandaloneIndexSnapshot.MAX_BYTES + 1, new JedisDataException("WRONGTYPE"))) {
            try (TestPool pool = new TestPool()) {
                owned(pool); pool.connection.reply(List.of(size, 1L, denied("multi")));
                assertThrows(JedisDataException.class, () -> aggregate(pool, "amount")); discarded(pool);
                assertFalse(pool.connection.names().contains("HGET"));
            }
        }
    }

    @Test void malformedTransactionTailAndNoauthAreNotDerivedAclErrors() {
        for (Object tail : List.of(bytes("OK"), new JedisDataException("ERR another transaction failure"),
                new JedisAccessControlException("NOAUTH Authentication required."))) {
            try (TestPool pool = new TestPool()) {
                pool.connection.reply(List.of(denied("multi"), 2L, 0L, bytes("a"), bytes("a"), tail));
                assertThrows(RuntimeException.class, () -> aggregate(pool, null)); discarded(pool);
            }
        }
    }

    @Test void queuedDenialCannotHideMalformedOrWrongtypeCompanion() {
        for (Object companion : List.of(bytes("unexpected"), new JedisDataException("WRONGTYPE"))) {
            try (TestPool pool = new TestPool()) {
                pool.connection.reply(List.of(bytes("OK"), bytes("QUEUED"), bytes("QUEUED"),
                        denied("getrange"), companion, execAbort()));
                assertThrows(RuntimeException.class, () -> aggregate(pool, null)); discarded(pool);
            }
        }
    }

    @Test void unwatchDenialAndNestedExecPermissionErrorsRemainFailures() {
        try (TestPool pool = new TestPool()) {
            pool.connection.reply(List.of(bytes("OK"), 1L, 0L, bytes("a"), bytes("a")));
            pool.connection.reply(List.of(denied("unwatch")));
            assertThrows(JedisAccessControlException.class, () -> aggregate(pool, "amount")); discarded(pool);
        }
        try (TestPool pool = new TestPool()) {
            pool.connection.reply(List.of(bytes("OK"), bytes("QUEUED"), bytes("QUEUED"),
                    bytes("QUEUED"), bytes("QUEUED"), List.of(2L, 0L, denied("getrange"), bytes("a"))));
            assertThrows(JedisAccessControlException.class, () -> aggregate(pool, null));
            assertEquals(1, pool.healthyReturns);
        }
    }

    @Test void ioFailureIsNotAnAlgorithmFallback() {
        try (TestPool pool = new TestPool()) {
            pool.connection.failureAtRead = 1;
            assertThrows(JedisConnectionException.class, () -> aggregate(pool, "amount")); discarded(pool);
        }
    }

    @Test void knownAclReplyCannotHideSubsequentConnectionLoss() {
        try (TestPool pool = new TestPool()) {
            pool.connection.disconnectAfterReads = 1;
            pool.connection.reply(List.of(denied("watch"), 2L, 0L, bytes("a"), bytes("a")));
            assertThrows(JedisConnectionException.class, () -> aggregate(pool, "amount")); discarded(pool);
        }
    }

    @Test void brokenReturnFailurePreventsFallbackEvenAfterVerifiedDenial() {
        try (TestPool pool = new TestPool()) {
            pool.failBrokenReturn = true;
            pool.connection.reply(List.of(denied("watch"), 2L, 0L, bytes("a"), bytes("a")));
            assertThrows(JedisConnectionException.class, () -> aggregate(pool, "amount")); discarded(pool);
        }
    }

    @Test void poolCleanupPermissionLookalikeCannotBecomeAlgorithmFallback() {
        try (TestPool pool = new TestPool()) {
            pool.failHealthyReturn = true;
            pool.connection.reply(List.of(bytes("OK"), bytes("QUEUED"), bytes("QUEUED"),
                    bytes("QUEUED"), bytes("QUEUED"), List.of(2L, 0L, bytes("a"), bytes("a"))));
            assertThrows(JedisAccessControlException.class, () -> aggregate(pool, null));
            assertEquals(1, pool.healthyReturns);
        }
    }

    private static Optional<RedisClientOperations.IndexAggregateBatch> aggregate(TestPool pool, String field) {
        return RedisStandaloneIndexSnapshot.aggregate(pool, ENTITIES, TOMBS, META, OWNERS, "a", field, true);
    }
    private static void owned(TestPool pool) {
        pool.connection.reply(List.of(bytes("OK"), 2L, 0L, bytes("a"), bytes("a")));
    }
    private static JedisAccessControlException denied(String command) {
        return new JedisAccessControlException("NOPERM User test has no permissions to run the '" + command + "' command");
    }
    private static JedisDataException execAbort() {
        return new JedisDataException("EXECABORT Transaction discarded because of previous errors.");
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void discarded(TestPool pool) {
        assertEquals(1, pool.brokenReturns); assertEquals(0, pool.healthyReturns);
        assertTrue(pool.connection.isBroken());
    }

    private static final class TestPool extends Pool<Connection> {
        final RedisStandaloneIndexSnapshotTest.FakeConnection connection = new RedisStandaloneIndexSnapshotTest.FakeConnection();
        int healthyReturns, brokenReturns;
        boolean failBrokenReturn, failHealthyReturn;
        TestPool() { super(new BasePooledObjectFactory<Connection>() {
            public Connection create() { throw new AssertionError("No network expected"); }
            public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
        }, config()); }
        private static GenericObjectPoolConfig<Connection> config() {
            var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false); return config;
        }
        @Override public Connection borrowObject(Duration wait) { return connection; }
        @Override public void returnResource(Connection value) {
            assertSame(connection, value); healthyReturns++;
            if (failHealthyReturn) throw denied("watch");
        }
        @Override public void returnBrokenResource(Connection value) {
            assertSame(connection, value); brokenReturns++;
            if (failBrokenReturn) throw new JedisConnectionException("Injected broken-resource disposal failure");
        }
    }
}
