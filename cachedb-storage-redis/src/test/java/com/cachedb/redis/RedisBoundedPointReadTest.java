package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisNoScriptException;
import redis.clients.jedis.executors.CommandExecutor;

class RedisBoundedPointReadTest {
    @Test void warmReadUsesTheStaticBinaryHexShaAndUnchangedBinaryArguments() throws Exception {
        var client = new RecordingClient(null);
        assertEquals("value", read(client).payload());
        assertEquals(1, client.shaCalls);
        assertEquals(0, client.evalCalls);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(RedisBoundedPointRead.SCRIPT.getBytes(StandardCharsets.UTF_8))),
                new String(client.sha, StandardCharsets.US_ASCII));
        assertEquals(40, client.sha.length);
        assertEquals(List.of("{point}:\u00fc", "{point}:t"), text(client.shaKeys));
        assertEquals(List.of("1024", "0", "0"), text(client.shaArgs));
        byte[] firstSha = client.sha;
        read(client);
        assertSame(firstSha, client.sha);
    }

    @Test void noScriptFallsBackExactlyOnceWithTheSameKeysAndArguments() {
        var client = new RecordingClient(null);
        client.shaFailure = new JedisNoScriptException("NOSCRIPT No matching script. Please use EVAL.");
        assertEquals("value", read(client).payload());
        assertFallback(client);
    }

    @Test void onlyDirectEvalshaCommandDenialsPreserveEvalOnlyAclCompatibility() {
        for (String message : List.of(
                "NOPERM this user has no permissions to run the 'evalsha' command",
                "NOPERM User eval_only has no permissions to run the 'evalsha' command")) {
            var client = new RecordingClient(null);
            client.shaFailure = new JedisAccessControlException(message);
            assertEquals("value", read(client).payload(), message);
            assertFallback(client);
        }
    }

    @Test void keyScriptAndOtherFailuresNeverExecuteEval() {
        for (RuntimeException failure : List.of(
                new JedisAccessControlException("NOPERM No permissions to access a key"),
                new JedisAccessControlException(
                        "NOPERM this user has no permissions to access one of the keys used as arguments"),
                new JedisAccessControlException("NOPERM this user has no permissions to run the 'mget' command"),
                new JedisAccessControlException("NOPERM User test has no permissions to run the 'strlen' command"),
                new JedisAccessControlException(
                        "ERR user_script: NOPERM this user has no permissions to run the 'evalsha' command"),
                new JedisAccessControlException("NOPERM User  has no permissions to run the 'evalsha' command"),
                new JedisAccessControlException("NOPERM this user has no permissions to run the 'eval' command"),
                new JedisAccessControlException((String) null),
                new JedisDataException("NOSCRIPT No matching script. Please use EVAL."),
                new JedisDataException("NOPERM this user has no permissions to run the 'evalsha' command"),
                new JedisDataException("ERR The user executing the script can't run this command or subcommand"),
                new JedisDataException("WRONGTYPE Operation against a key holding the wrong kind of value"),
                new JedisDataException("CACHEDB_READ_BUDGET"),
                new JedisDataException("CACHEDB_READ_SHAPE"),
                new JedisDataException("ERR unknown command 'evalsha'"),
                new JedisConnectionException("Read timed out"))) {
            var client = new RecordingClient(null);
            client.shaFailure = failure;
            assertSame(failure, assertThrows(RuntimeException.class, () -> read(client)), failure.getMessage());
            assertEquals(1, client.shaCalls);
            assertEquals(0, client.evalCalls);
        }
    }

    @Test void wrappedOrSuppressedCommandDenialsNeverExecuteEval() {
        var caused = new JedisAccessControlException("NOPERM this user has no permissions to run the 'evalsha' command");
        caused.initCause(new IllegalStateException("wrapped"));
        var suppressed = new JedisAccessControlException(caused.getMessage());
        suppressed.addSuppressed(new IllegalStateException("suppressed"));
        for (var failure : List.of(caused, suppressed)) {
            var client = new RecordingClient(null);
            client.shaFailure = failure;
            assertSame(failure, assertThrows(JedisAccessControlException.class, () -> read(client)));
            assertEquals(0, client.evalCalls);
        }
    }

    @Test void fallbackFailureIsPropagatedWithoutAnotherAttempt() {
        for (var trigger : List.of(new JedisNoScriptException("NOSCRIPT missing"),
                new JedisAccessControlException("NOPERM this user has no permissions to run the 'evalsha' command"))) {
            var client = new RecordingClient(null);
            client.shaFailure = trigger;
            var failure = new JedisNoScriptException("NOSCRIPT fallback failure is not retried");
            client.evalFailure = failure;
            assertSame(failure, assertThrows(JedisNoScriptException.class, () -> read(client)));
            assertFallback(client);
        }
    }

    private static RedisPointRead read(RecordingClient client) {
        return RedisBoundedPointRead.read(client,
                List.of(new RedisBoundedPointRead.KeyPair("{point}:\u00fc", "{point}:t", false)), 1024)[0];
    }

    private static void assertFallback(RecordingClient client) {
        assertEquals(1, client.shaCalls);
        assertEquals(1, client.evalCalls);
        assertArrayEquals(RedisBoundedPointRead.SCRIPT.getBytes(StandardCharsets.UTF_8), client.script);
        assertSame(client.shaKeys, client.evalKeys);
        assertSame(client.shaArgs, client.evalArgs);
    }

    private static List<String> text(List<byte[]> values) {
        return values.stream().map(value -> new String(value, StandardCharsets.UTF_8)).toList();
    }

    /** Records the public binary overloads; optional delegation uses a real Redis connection. */
    static final class RecordingClient implements RedisClientOperations {
        final RedisClientOperations delegate;
        int shaCalls, evalCalls;
        byte[] sha, script;
        List<byte[]> shaKeys, shaArgs, evalKeys, evalArgs;
        RuntimeException shaFailure, evalFailure;
        final UnifiedJedis commands = new UnifiedJedis(new CommandExecutor() {
            @Override public <T> T executeCommand(CommandObject<T> command) {
                throw new AssertionError("Only binary EVALSHA/EVAL are permitted");
            }
            @Override public void close() {}
        }) {
            @Override public Object evalsha(byte[] digest, List<byte[]> keys, List<byte[]> args) {
                shaCalls++;
                sha = digest; shaKeys = keys; shaArgs = args;
                if (shaFailure != null) throw shaFailure;
                return delegate == null ? reply() : delegate.commands().evalsha(digest, keys, args);
            }
            @Override public Object eval(byte[] source, List<byte[]> keys, List<byte[]> args) {
                evalCalls++;
                script = source; evalKeys = keys; evalArgs = args;
                if (evalFailure != null) throw evalFailure;
                return delegate == null ? reply() : delegate.commands().eval(source, keys, args);
            }
        };
        RecordingClient(RedisClientOperations delegate) { this.delegate = delegate; }
        @Override public UnifiedJedis commands() { return commands; }
        @Override public boolean clustered() { return delegate != null && delegate.clustered(); }
        @Override public void close() {}
        private static Object reply() {
            return List.of(Arrays.asList("value".getBytes(StandardCharsets.UTF_8), null), List.of(-3L, -3L));
        }
    }
}
