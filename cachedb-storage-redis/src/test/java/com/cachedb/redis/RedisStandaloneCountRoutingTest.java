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
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.*;

class RedisStandaloneCountRoutingTest {
    @Test void binaryMultiwindowCountUsesOwnedNativePrimaryAndRetainsSmallTailExclusion() {
        try (Client client = new Client()) {
            var operations = new JedisPooledOperations(client);
            assertTrue(RedisBinaryCountKeys.supports(operations, new RedisKeyStrategy()));
            client.factory.reply(countExec(512, true, null));
            client.factory.reply(countExec(512, true, null));
            var ids = Collections.nCopies(1024, "id");
            assertEquals(1024, operations.binaryIndexCounts(
                    new RedisBinaryCountKeys(new RedisKeyStrategy(), "ns", ids, "a"), "a").orElseThrow());
            assertEquals(0, client.factory.evaluations);
            assertEquals(2, Collections.frequency(client.factory.commands, "MULTI"));
            assertEquals(2, Collections.frequency(client.factory.commands, "EXEC"));
            assertEquals(4, Collections.frequency(client.factory.commands, "EXISTS"));
            assertEquals(1024, Collections.frequency(client.factory.commands, "GETRANGE"));
            assertEquals(1032, client.factory.commands.size());
            assertEquals(0, client.getPool().getNumActive());
            client.factory.commands.clear();
            assertTrue(operations.binaryIndexCounts(new RedisBinaryCountKeys(new RedisKeyStrategy(), "ns",
                    Collections.nCopies(513, "id"), "a"), "a").isEmpty());
            assertTrue(client.factory.commands.isEmpty());
        }
    }

    @Test void largeTenantCountUsesOneLuaCommandAndNoNativeTransaction() {
        try (Client client = new Client()) {
            long borrowed = client.getPool().getBorrowedCount();
            assertEquals(512, count(client, 512, true).count());
            assertEquals(borrowed + 1, client.getPool().getBorrowedCount());
            assertEquals(List.of("EVAL"), client.factory.commands);
        }
    }

    @Test void exactOwnerThresholdDoesNotChangeSmallTenantOrLargeGlobalCount() {
        for (boolean tenant : List.of(false, true)) {
            int size = tenant ? 32 : 512;
            try (Client client = new Client()) {
                client.factory.reply(countExec(size, tenant, null));
                assertEquals(size, count(client, size, tenant).count());
                assertEquals(0, client.factory.evaluations);
                assertEquals(tenant ? size : 0, Collections.frequency(client.factory.commands, "GETRANGE"));
                assertFalse(client.factory.commands.contains("WATCH"));
            }
        }
        try (Client client = new Client()) {
            assertEquals(33, count(client, 33, true).count());
            assertEquals(List.of("EVAL"), client.factory.commands);
        }
    }

    @Test void metadataAggregateWatchBudgetIsThirtyTwoKeysForGlobalAndTenantRequests() {
        for (boolean tenant : List.of(false, true)) {
            int limit = tenant ? 8 : 10;
            for (int size : List.of(limit, limit + 1)) {
                try (Client client = new Client()) {
                    client.factory.evalResponse = List.of((long) size, Collections.nCopies(size, bytes("0.125")), List.of());
                    if (size == limit) metadataReplies(client.factory, size, tenant);
                    var result = new JedisPooledOperations(client).aggregateIndexBatch(
                            keys("entity", size), keys("tomb", size), keys("meta", size),
                            tenant ? keys("owner", size) : null, tenant ? "a" : null, "amount", false).orElseThrow();
                    assertEquals(size, result.count());
                    assertEquals(Collections.nCopies(size, "0.125"), result.values());
                    assertEquals(size == limit ? 0 : 1, client.factory.evaluations);
                    assertEquals(size == limit ? 1 : 0, Collections.frequency(client.factory.commands, "WATCH"));
                }
            }
        }
    }

    @Test void drainedEvalDenialAlsoRetainsLargeNumericAggregateWithinNativeLimits() {
        try (Client client = new Client()) {
            client.factory.evalError = denied("eval"); metadataReplies(client.factory, 33, true);
            var result = new JedisPooledOperations(client).aggregateIndexBatch(keys("entity", 33), keys("tomb", 33),
                    keys("meta", 33), keys("owner", 33), "a", "amount", false).orElseThrow();
            assertEquals(Collections.nCopies(33, "0.125"), result.values());
            assertEquals(1, client.factory.evaluations);
            assertEquals(1, Collections.frequency(client.factory.commands, "WATCH"));
            assertEquals(1, client.factory.destroyedAtFirstNativeSend);
        }
    }

    @Test void drainedEvalDenialIsDiscardedBeforeOneNativeFallbackBorrow() {
        try (Client client = new Client()) {
            client.factory.evalError = denied("eval");
            client.factory.reply(countExec(33, true, bytes("ax")));
            long borrowed = client.getPool().getBorrowedCount();
            assertEquals(32, count(client, 33, true).count());
            assertEquals(borrowed + 2, client.getPool().getBorrowedCount());
            assertEquals(1, client.factory.evaluations);
            assertEquals(1, client.factory.destroyed);
            assertEquals(2, client.factory.created);
            assertEquals(1, client.factory.destroyedAtFirstNativeSend);
            assertEquals(1, Collections.frequency(client.factory.commands, "EXEC"));
            assertEquals(0, client.getPool().getNumActive());
        }
    }

    @Test void largeFacetEvalDenialUsesOneBoundedNativeSnapshot() {
        try (Client client = new Client()) {
            client.factory.evalError = denied("eval");
            facetReplies(client.factory, false);
            var rows = new JedisPooledOperations(client).facetIndexBatch(
                    keys("entity", 11), keys("tomb", 11), keys("meta", 11), null, null, "group", null).orElseThrow();
            assertEquals(11, rows.rows().size());
            assertEquals(1, client.factory.evaluations);
            assertEquals(1, Collections.frequency(client.factory.commands, "WATCH"));
            assertEquals(22, Collections.frequency(client.factory.commands, "HSTRLEN"));
            assertEquals(11, Collections.frequency(client.factory.commands, "HGET"));
            assertEquals(1, client.factory.destroyedAtFirstNativeSend);
            assertEquals(0, client.getPool().getNumActive());
        }
    }

    @Test void deniedNativeFallbackDoesNotReenterLuaOrReturnFalseZero() {
        try (Client client = new Client()) {
            var original = denied("eval"); client.factory.evalError = original;
            var reply = new ArrayList<Object>(); reply.add(denied("multi")); reply.add(33L); reply.add(0L);
            for (int i = 0; i < 33; i++) reply.add(bytes("a"));
            reply.add(new JedisDataException("ERR EXEC without MULTI")); client.factory.reply(reply);
            assertSame(original, assertThrows(JedisAccessControlException.class, () -> count(client, 33, true)));
            assertEquals(1, client.factory.evaluations);
            assertEquals(2, client.factory.destroyed);
            assertEquals(0, client.getPool().getNumActive());
        }
    }

    @Test void nativeWrongtypeAfterEvalDenialPropagatesWithoutAnotherAttempt() {
        try (Client client = new Client()) {
            client.factory.evalError = denied("eval");
            var wrongtype = new JedisDataException("WRONGTYPE");
            client.factory.reply(countExec(33, true, wrongtype));
            assertSame(wrongtype, assertThrows(JedisDataException.class, () -> count(client, 33, true)));
            assertEquals(1, client.factory.evaluations);
            assertEquals(0, client.getPool().getNumActive());
        }
    }

    @Test void nativeIoAfterEvalDenialPropagatesWithoutAnotherAttempt() {
        try (Client client = new Client()) {
            client.factory.evalError = denied("eval");
            var io = new JedisConnectionException("Injected native receive failure"); client.factory.nativeFailure = io;
            assertSame(io, assertThrows(JedisConnectionException.class, () -> count(client, 33, true)));
            assertEquals(1, client.factory.evaluations);
            assertEquals(2, client.factory.destroyed);
        }
    }

    @Test void watchAbortAfterEvalDenialDoesNotRetryOrReturnAnEmptyResult() {
        try (Client client = new Client()) {
            var original = denied("eval"); client.factory.evalError = original;
            facetReplies(client.factory, true);
            assertSame(original, assertThrows(JedisAccessControlException.class,
                    () -> new JedisPooledOperations(client).facetIndexBatch(
                            keys("entity", 11), keys("tomb", 11), keys("meta", 11), null, null, "group", null)));
            assertEquals(1, client.factory.evaluations);
            assertEquals(1, Collections.frequency(client.factory.commands, "EXEC"));
            assertEquals(0, client.getPool().getNumActive());
        }
    }

    @Test void authenticationKeyAclScriptAndWrongtypeErrorsDoNotSelectNative() {
        var wrapped = new JedisAccessControlException(denied("eval").getMessage(), new JedisConnectionException("IO"));
        var suppressed = denied("eval"); suppressed.addSuppressed(new JedisConnectionException("IO"));
        for (JedisDataException failure : List.of(
                new JedisAccessControlException("NOAUTH Authentication required."),
                new JedisAccessControlException("WRONGPASS invalid username-password pair"),
                new JedisAccessControlException("NOPERM No permissions to access a key"),
                new JedisAccessControlException("NOPERM User test has no permissions to run the 'getrange' command"),
                new JedisAccessControlException("NOPERM ACLs rules changed between queue and EXEC"),
                new JedisAccessControlException("NOPERM"),
                new JedisAccessControlException("NOPERM User  has no permissions to run the 'eval' command"),
                new JedisDataException(denied("eval").getMessage()),
                new JedisDataException("EXECABORT Transaction discarded because of: " + denied("eval").getMessage()),
                new JedisDataException("ERR ACL failure in script"), new JedisDataException("WRONGTYPE"), wrapped, suppressed)) {
            try (Client client = new Client()) {
                client.factory.evalError = failure;
                assertSame(failure, assertThrows(JedisDataException.class, () -> count(client, 33, true)));
                assertEquals(List.of("EVAL"), client.factory.commands);
            }
        }
    }

    @Test void ioAndUnalignedPermissionThrowsCannotMasqueradeAsDrainedEvalReplies() {
        for (RuntimeException failure : List.of(new JedisConnectionException("IO"), denied("eval"))) {
            try (Client client = new Client()) {
                client.factory.evalThrow = failure;
                assertSame(failure, assertThrows(RuntimeException.class, () -> count(client, 33, true)));
                assertEquals(List.of("EVAL"), client.factory.commands);
                assertEquals(1, client.factory.destroyed);
            }
        }
    }

    @Test void connectionLossAfterEvalReplyPreventsAlgorithmFallback() {
        try (Client client = new Client()) {
            client.factory.evalError = denied("eval"); client.factory.disconnectAfterEval = true;
            assertThrows(JedisConnectionException.class, () -> count(client, 33, true));
            assertEquals(List.of("EVAL"), client.factory.commands);
            assertEquals(1, client.factory.destroyed);
            assertEquals(0, client.getPool().getNumIdle());
            assertEquals(0, client.getPool().getNumActive());
        }
    }

    @Test void connectionLossAfterSuccessfulEvalReplyDiscardsWithoutRetry() {
        try (Client client = new Client()) {
            client.factory.disconnectAfterEval = true;
            assertEquals(33, count(client, 33, true).count());
            assertEquals(List.of("EVAL"), client.factory.commands);
            assertEquals(1, client.factory.destroyed);
            assertEquals(0, client.getPool().getNumIdle());
            assertEquals(0, client.getPool().getNumActive());
        }
    }

    @Test void failedDiscardAfterEvalDenialPreventsNativeFallbackEvenForPermissionLookalikes() {
        for (RuntimeException failure : List.of(new JedisConnectionException("Discard IO"), denied("eval"))) {
            try (Client client = new Client()) {
                client.factory.evalError = denied("eval"); client.factory.discardFailure = failure;
                assertThrows(RuntimeException.class, () -> count(client, 33, true));
                assertEquals(List.of("EVAL"), client.factory.commands);
                assertEquals(1, client.factory.created);
            }
        }
    }

    @Test void malformedLuaReplyDoesNotSelectAnotherAlgorithm() {
        try (Client client = new Client()) {
            client.factory.evalResponse = List.of(33L);
            assertThrows(IllegalStateException.class, () -> count(client, 33, true));
            assertEquals(List.of("EVAL"), client.factory.commands);
        }
    }

    @Test void facetDoesNotConvertKeyPermissionOrWrongtypeErrorsIntoNativeFallback() {
        for (var failure : List.of(new JedisAccessControlException("NOPERM No permissions to access a key"),
                new JedisDataException("WRONGTYPE"))) {
            try (Client client = new Client()) {
                client.factory.evalError = failure;
                assertSame(failure, assertThrows(JedisDataException.class,
                        () -> new JedisPooledOperations(client).facetIndexBatch(
                                keys("entity", 11), keys("tomb", 11), keys("meta", 11), null, null, "group", null)));
                assertEquals(List.of("EVAL"), client.factory.commands);
            }
        }
    }

    @Test void rowAndFieldBoundsRemainValidatedBeforeEitherAlgorithm() {
        try (Client client = new Client()) {
            assertThrows(IllegalArgumentException.class, () -> count(client, 513, true));
            assertThrows(IllegalArgumentException.class, () -> new JedisPooledOperations(client).aggregateIndexBatch(
                    keys("entity", 33), keys("tomb", 33), keys("meta", 33), keys("owner", 33), "a", "f".repeat(4097), true));
            assertTrue(client.factory.commands.isEmpty());
        }
    }

    private static RedisClientOperations.IndexAggregateBatch count(Client client, int size, boolean tenant) {
        return new JedisPooledOperations(client).aggregateIndexBatch(keys("entity", size), keys("tomb", size),
                keys("meta", size), tenant ? keys("owner", size) : null, tenant ? "a" : null, null, true).orElseThrow();
    }
    private static List<String> keys(String prefix, int size) {
        var keys = new ArrayList<String>(size); for (int i = 0; i < size; i++) keys.add(prefix + ":" + i); return keys;
    }
    private static List<Object> countExec(int size, boolean tenant, Object lastOwner) {
        var values = new ArrayList<Object>(); values.add((long) size); values.add(0L);
        if (tenant) for (int i = 0; i < size; i++) values.add(i == size - 1 && lastOwner != null ? lastOwner : bytes("a"));
        var reply = new ArrayList<Object>(); reply.add(bytes("OK"));
        for (int i = 0; i < values.size(); i++) reply.add(bytes("QUEUED"));
        reply.add(values); return reply;
    }
    private static void metadataReplies(Factory factory, int size, boolean tenant) {
        var proof = new ArrayList<Object>(); proof.add(bytes("OK")); proof.add((long) size); proof.add(0L);
        if (tenant) for (int i = 0; i < size; i++) proof.add(bytes("a"));
        factory.reply(proof);
        var lengths = new ArrayList<Object>(Collections.nCopies(size, 5L)); lengths.add(bytes("OK")); factory.reply(lengths);
        metadataExec(factory, proof.subList(1, proof.size()), lengths.subList(0, size),
                Collections.nCopies(size, bytes("0.125")), false);
    }
    private static void facetReplies(Factory factory, boolean abort) {
        factory.reply(List.of(bytes("OK"), 11L, 0L));
        var lengths = new ArrayList<Object>(Collections.nCopies(11, 1L)); lengths.add(bytes("OK")); factory.reply(lengths);
        metadataExec(factory, List.of(11L, 0L), lengths.subList(0, 11), Collections.nCopies(11, bytes("g")), abort);
    }
    private static void metadataExec(Factory factory, List<Object> proof, List<?> sizes, List<?> values, boolean abort) {
        factory.reply(Collections.nCopies(proof.size(), bytes("QUEUED")));
        if (!sizes.isEmpty()) factory.reply(Collections.nCopies(sizes.size(), bytes("QUEUED")));
        var committed = new ArrayList<Object>(proof);
        committed.addAll(sizes);
        committed.addAll(values);
        var finish = new ArrayList<Object>(Collections.nCopies(values.size(), bytes("QUEUED")));
        finish.add(abort ? null : committed);
        factory.reply(finish);
    }
    private static JedisAccessControlException denied(String command) {
        return new JedisAccessControlException("NOPERM User test has no permissions to run the '" + command + "' command");
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static final class Client extends JedisPooled {
        final Factory factory;
        Client() { this(new Factory()); }
        private Client(Factory factory) { super(factory, config()); this.factory = factory; }
        private static GenericObjectPoolConfig<Connection> config() {
            var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false);
            config.setMaxTotal(1); config.setMaxIdle(1); config.setMaxWait(Duration.ofSeconds(2)); return config;
        }
    }
    private static final class Factory extends BasePooledObjectFactory<Connection> {
        final Deque<List<Object>> replies = new ArrayDeque<>();
        final List<String> commands = new ArrayList<>();
        int created, destroyed, evaluations, destroyedAtFirstNativeSend = -1;
        JedisDataException evalError;
        RuntimeException evalThrow, nativeFailure, discardFailure;
        Object evalResponse;
        boolean disconnectAfterEval;
        public Connection create() { created++; return new FakeConnection(this); }
        public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
        @Override public void destroyObject(PooledObject<Connection> value) {
            destroyed++; value.getObject().setBroken();
            if (discardFailure != null) throw discardFailure;
        }
        void reply(List<Object> reply) { replies.addLast(reply); }
    }
    private static final class FakeConnection extends Connection {
        final Factory factory;
        final List<Object> wireReplies = new ArrayList<>();
        boolean connected = true;
        int rowCount;
        FakeConnection(Factory factory) { this.factory = factory; protocol = RedisProtocol.RESP2; }
        @Override public boolean isConnected() { return connected; }
        @Override public void sendCommand(CommandArguments args) {
            String command = args.getCommand().toString(); factory.commands.add(command);
            if (command.equals("EVAL")) {
                factory.evaluations++;
                var raw = new ArrayList<String>();
                for (var arg : args) raw.add(new String(arg.getRaw(), StandardCharsets.UTF_8));
                rowCount = Integer.parseInt(raw.get(5 + Integer.parseInt(raw.get(2))));
                Object reply = factory.evalError != null ? factory.evalError : factory.evalResponse != null
                        ? factory.evalResponse : List.of((long) rowCount, List.of(), List.of());
                wireReplies.add(reply);
            } else if (factory.destroyedAtFirstNativeSend < 0) factory.destroyedAtFirstNativeSend = factory.destroyed;
        }
        @Override protected void flush() {}
        @Override protected Object readProtocolWithCheckingBroken() {
            Object reply = wireReplies.removeFirst();
            if (reply instanceof JedisDataException failure) throw failure;
            return reply;
        }
        @Override public List<Object> getMany(int count) {
            if (!wireReplies.isEmpty()) {
                if (factory.evalThrow != null) throw factory.evalThrow;
                List<Object> result = super.getMany(count);
                if (factory.disconnectAfterEval) connected = false;
                return result;
            }
            if (factory.nativeFailure != null) throw factory.nativeFailure;
            var reply = factory.replies.removeFirst(); assertEquals(count, reply.size()); return reply;
        }
    }
}
