package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisClusterOperationException;
import redis.clients.jedis.exceptions.JedisDataException;

class RedisReadClusterTransportTest {
    private static RedisPointRead read(RedisClientOperations client) {
        return RedisBoundedPointRead.read(client,
                List.of(new RedisBoundedPointRead.KeyPair("{redirect}:e", "{redirect}:t", true)), 1024)[0];
    }

    @Test void movedUsesNormalExecutorRefreshesSlotsAndNextReadNeedsNoUnrelatedCommand() throws Exception {
        try (var peers = new RedisReadRedirectServer(); var client = peers.connect(3)) {
            peers.fault = RedisReadRedirectServer.Fault.MOVED;
            assertEquals("row-ğ", read(client).payload());
            assertEquals(1, peers.first.evals.get());
            assertEquals(1, peers.second.evals.get());
            assertTrue(peers.first.slots.get() >= 2, "MOVED must renew the originally cached slot map");
            assertEquals("row-ğ", read(client).payload());
            assertEquals(1, peers.first.evals.get(), "A second read must route directly without unrelated refresh");
            assertEquals(2, peers.second.evals.get());
            peers.assertHealthy();
        }
    }

    @Test void askUsesAskingOnTargetConnectionWithoutPermanentlyRemappingSlot() throws Exception {
        try (var peers = new RedisReadRedirectServer(); var client = peers.connect(3)) {
            int discovered = peers.first.slots.get() + peers.second.slots.get();
            peers.fault = RedisReadRedirectServer.Fault.ASK;
            for (int i = 0; i < 2; i++) {
                RedisPointRead result = read(client);
                assertEquals("row-ğ", result.payload());
                assertEquals(-1, result.ttlMillis());
                assertNull(result.tombstone());
            }
            assertEquals(2, peers.first.evals.get(), "ASK must not permanently replace the slot owner");
            assertEquals(2, peers.second.evals.get());
            assertEquals(2, peers.second.asking.get());
            assertEquals(discovered, peers.first.slots.get() + peers.second.slots.get());
            peers.assertHealthy();
        }
    }

    @Test void movedLoopStopsAtConfiguredAttemptsInsteadOfRetryingForever() throws Exception {
        assertBoundedRedirectLoop(RedisReadRedirectServer.Fault.MOVED_LOOP);
    }

    @Test void askLoopStopsAtConfiguredAttemptsInsteadOfRetryingForever() throws Exception {
        assertBoundedRedirectLoop(RedisReadRedirectServer.Fault.ASK_LOOP);
    }

    private static void assertBoundedRedirectLoop(RedisReadRedirectServer.Fault fault) throws Exception {
        try (var peers = new RedisReadRedirectServer(); var client = peers.connect(3)) {
            peers.fault = fault;
            var error = assertThrows(JedisClusterOperationException.class, () -> read(client));
            assertEquals(3, peers.first.evals.get() + peers.second.evals.get());
            assertEquals(1, error.getSuppressed().length, "Jedis must retain the final redirect");
            if (fault == RedisReadRedirectServer.Fault.ASK_LOOP)
                assertEquals(2, peers.first.asking.get() + peers.second.asking.get());
            peers.fault = RedisReadRedirectServer.Fault.NONE;
            assertEquals("row-ğ", read(client).payload(), "Recovery cannot depend on another application command");
            peers.assertHealthy();
        }
    }

    @Test void socketDisconnectIsNotSwallowedAndUsesOnlyJedisConfiguredRetryBudget() throws Exception {
        try (var peers = new RedisReadRedirectServer(); var client = peers.connect(2)) {
            peers.fault = RedisReadRedirectServer.Fault.DISCONNECT;
            assertThrows(JedisClusterOperationException.class, () -> read(client));
            assertEquals(2, peers.first.evals.get() + peers.second.evals.get());
            peers.fault = RedisReadRedirectServer.Fault.NONE;
            assertEquals("row-ğ", read(client).payload());
            peers.assertHealthy();
        }
    }

    @Test void aclFailureIsNotRetriedOrConvertedIntoUnguardedGet() throws Exception {
        assertSingleAttempt(RedisReadRedirectServer.Fault.ACL, "NOPERM scripted ACL denial");
    }

    @Test void singletonBudgetErrorIsNotRetriedByClusterDriver() throws Exception {
        assertSingleAttempt(RedisReadRedirectServer.Fault.BUDGET, "CACHEDB_READ_BUDGET");
    }

    private static void assertSingleAttempt(RedisReadRedirectServer.Fault fault, String message) throws Exception {
        try (var peers = new RedisReadRedirectServer(); var client = peers.connect(3)) {
            peers.fault = fault;
            assertEquals(message, assertThrows(JedisDataException.class, () -> read(client)).getMessage());
            assertEquals(1, peers.first.evals.get() + peers.second.evals.get());
            peers.assertHealthy();
        }
    }
}
