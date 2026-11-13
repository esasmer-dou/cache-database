package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisDataException;

/** Actual Redis Lua/schema/ACL controls only; these do not attest a SQL lease or runtime closure. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(30)
class RedisInitialGenerationHardeningRedisTest {
    private static final String TOKEN = "a".repeat(64);

    @Test void acceptsExactLegacyAndActivatedInitialSchemasIncludingLargestExactFinalizeEpoch() {
        try (var f = new Fixture()) {
            assertEquals(List.of(0L, 0L, 0L), f.ledger());
            f.admin.hset(f.stage, "compaction_ack", TOKEN);
            assertEquals(7, f.admin.hlen(f.stage));
            assertEquals(List.of(0L, 0L, 0L), f.ledger());
            f.active("9007199254740991");
            assertEquals(19, f.admin.hlen(f.stage));
            assertEquals(List.of(0L, 0L, 0L), f.ledger());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "00", "01", "-1", "+1", "1.0", "1e3", " 1", "1 ",
            "9007199254740992", "9223372036854775807"})
    void rejectsNoncanonicalOrInexactFinalizeEpoch(String epoch) {
        try (var f = new Fixture()) {
            f.active(epoch);
            f.rejected(() -> f.ledger());
        }
    }

    @Test void rejectsUnknownInitialAndOrdinaryFieldsInsteadOfAcceptingAnInitialPrefix() {
        try (var f = new Fixture()) {
            for (String field : List.of("i:unreviewed", "i:p:not-a-uuid", "unreviewed")) {
                f.admin.hset(f.stage, field, "1");
                f.rejected(() -> f.ledger());
                f.admin.hdel(f.stage, field);
            }
            f.admin.hdel(f.stage, "epoch");
            f.rejected(() -> f.ledger());
        }
    }

    @Test void rejectsReceiptCounterDriftAndMissingActiveMetadata() {
        try (var f = new Fixture()) {
            f.admin.hset(f.stage, Map.of("i:operation", f.operation, "i:plan", TOKEN, "i:manifest", TOKEN,
                    "i:rows", "1", "i:bytes", "7", "i:pages", "1", f.receipt, TOKEN + ":1:7"));
            assertEquals(List.of(1L, 7L, 1L), f.ledger());
            f.admin.hset(f.stage, "i:bytes", "8");
            f.rejected(() -> f.ledger());
            f.admin.hset(f.stage, "i:bytes", "7");
            f.admin.hset(f.stage, "i:active", TOKEN);
            f.rejected(() -> f.ledger());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "129", "-1", "1.0"})
    void rejectsNoncanonicalOrOutOfRangeReceiptRowCount(String rows) {
        try (var f = new Fixture()) {
            f.admin.hset(f.stage, f.receipt, TOKEN + ":" + rows + ":7");
            f.rejected(() -> f.ledger());
        }
    }

    @Test void oversizedReceiptIsRejectedBeforeAnAclDeniedHget() {
        try (var f = new Fixture(); var reader = f.denied("hget", false)) {
            f.admin.hset(f.stage, f.receipt, "x".repeat(4097));
            f.rejected(() -> reader.eval(RedisInitialSourceHydrator.STAGE + "return {ledger(KEYS[1])}",
                    List.of(f.stage), List.of()));
            assertEquals(4097, f.admin.hstrlen(f.stage, f.receipt));
            assertEquals(7, f.admin.hlen(f.stage));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void oversizedPayloadVersionAndOwnerAreRejectedBeforeAnyAclDeniedGet(int position) {
        try (var f = new Fixture(); var reader = f.denied("get", false)) {
            String key = f.keys.get(position);
            f.admin.set(key, "x".repeat(4097));
            f.rejected(() -> reader.eval(RedisInitialSourceHydrator.PAGE, f.keys, f.arguments));
            assertEquals(4097, f.admin.strlen(key));
            assertEquals(6, f.admin.hlen(f.stage));
            assertEquals(0, f.admin.zcard(f.hot));
            assertEquals(0, f.admin.hlen(f.meta));
        }
    }

    @Test void oversizedMetadataIsRejectedBeforeAnAclDeniedMetadataHget() {
        try (var f = new Fixture()) {
            f.admin.eval(RedisInitialSourceHydrator.PAGE, f.keys, f.arguments);
            f.admin.hset(f.meta, "field", "x".repeat(4097));
            var before = f.admin.hgetAll(f.stage);
            try (var reader = f.denied("hget", true)) {
                f.rejected(() -> reader.eval(RedisInitialSourceHydrator.PAGE, f.keys, f.arguments));
            }
            assertEquals(before, f.admin.hgetAll(f.stage));
            assertEquals(4097, f.admin.hstrlen(f.meta, "field"));
        }
    }

    @Test void exactReplayIsIdempotentAndShortOrEmptyConflictsStayConflicts() {
        try (var f = new Fixture()) {
            var first = f.admin.eval(RedisInitialSourceHydrator.PAGE, f.keys, f.arguments);
            var stage = f.admin.hgetAll(f.stage);
            var second = f.admin.eval(RedisInitialSourceHydrator.PAGE, f.keys, f.arguments);
            assertInstanceOf(List.class, first);
            assertInstanceOf(List.class, second);
            assertEquals(stage, f.admin.hgetAll(f.stage));
            assertEquals(1, f.admin.zcard(f.hot));
            for (String conflict : List.of("", "foreign")) {
                f.admin.set(f.keys.get(4), conflict);
                f.rejected(() -> f.admin.eval(RedisInitialSourceHydrator.PAGE, f.keys, f.arguments));
                assertEquals(stage, f.admin.hgetAll(f.stage));
                assertEquals("payload", f.admin.get(f.keys.get(2)));
            }
        }
    }

    @Test void wrongTypePayloadAndOwnerRemainRejectedWithoutMutation() {
        try (var f = new Fixture()) {
            for (int position : List.of(2, 4)) {
                f.admin.hset(f.keys.get(position), "wrong", "type");
                f.rejected(() -> f.admin.eval(RedisInitialSourceHydrator.PAGE, f.keys, f.arguments));
                assertEquals(6, f.admin.hlen(f.stage));
                assertEquals(0, f.admin.zcard(f.hot));
                f.admin.del(f.keys.get(position));
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String prefix = "initial_guard_" + UUID.randomUUID().toString().replace("-", "");
        final String stage = prefix + ":generation", hot = prefix + ":hot", meta = prefix + ":meta";
        final String operation = UUID.randomUUID().toString();
        final String receipt = "i:p:" + UUID.randomUUID();
        final String host = System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1");
        final int port = Integer.getInteger("cachedb.snapshot.redis.port", 6379);
        final JedisPooled admin = client(null, null);
        final List<String> users = new ArrayList<>();
        final List<String> keys = List.of(stage, hot, prefix + ":payload", prefix + ":version",
                prefix + ":owner", prefix + ":tomb", meta, prefix + ":quota");
        final List<String> arguments = List.of(operation, TOKEN, TOKEN, receipt, TOKEN, "1", "7", "2",
                "1048576", "0", "16", "a", "H", "1", "7", "payload", "2",
                "DEL", "7", "0", "HSET", "7", "2", "field", "value");

        Fixture() {
            admin.hset(stage, Map.of("state", "CLOSED", "generation", "1", "operation", operation,
                    "owner", UUID.randomUUID().toString(), "epoch", "1", "run_id", "a".repeat(40)));
        }
        void active(String epoch) {
            admin.hset(stage, Map.of("i:operation", operation, "i:plan", TOKEN, "i:manifest", TOKEN,
                    "i:rows", "1", "i:bytes", "7", "i:pages", "0"));
            admin.hset(stage, Map.of("i:verified", TOKEN, "i:deadline", "0", "i:window", TOKEN,
                    "i:active", TOKEN, "i:active_range", TOKEN, "i:finalize_epoch", epoch));
        }
        Object ledger() {
            return admin.eval(RedisInitialSourceHydrator.STAGE + "return {ledger(KEYS[1])}", List.of(stage), List.of());
        }
        JedisPooled denied(String command, boolean allowStageHget) {
            String user = prefix + "_" + users.size(), password = UUID.randomUUID().toString();
            var rules = new ArrayList<>(List.of("reset", "on", ">" + password, "+@all", "-" + command, "~" + prefix + ":*"));
            if (allowStageHget) rules.add("(+hget ~" + stage + ")");
            rules.addFirst(user); rules.addFirst("SETUSER");
            Object reply=admin.sendCommand(Protocol.Command.ACL,rules.toArray(String[]::new));
            if(reply instanceof byte[] bytes) assertArrayEquals(new byte[]{'O','K'},bytes);
            else assertEquals("OK",assertInstanceOf(String.class,reply));
            users.add(user);
            return client(user, password);
        }
        JedisPooled client(String user, String password) {
            var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(2000)
                    .blockingSocketTimeoutMillis(2000);
            if (user != null) config.user(user).password(password);
            var client = new JedisPooled(new HostAndPort(host, port), config.build());
            client.getPool().setMaxTotal(2);
            client.getPool().setMaxWait(Duration.ofSeconds(2));
            return client;
        }
        void rejected(org.junit.jupiter.api.function.Executable action) {
            var error = assertThrows(JedisDataException.class, action);
            assertTrue(error.getMessage().contains("CDB_INITIAL_INCOMPLETE"), "Expected bounded corruption rejection, not ACL failure");
            assertFalse(error.getMessage().contains("NOPERM"));
            assertFalse(error.getMessage().contains("ACL failure"));
        }
        public void close() {
            try {
                for (String user : users) assertEquals(Long.valueOf(1),admin.sendCommand(Protocol.Command.ACL,"DELUSER",user));
                admin.del(keys.toArray(String[]::new));
            } finally { admin.close(); }
        }
    }
}
