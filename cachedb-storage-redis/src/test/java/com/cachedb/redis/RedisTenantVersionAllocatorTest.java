package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Native component tests, not a claim that every producer/replay/L1 path is integrated. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(60)
class RedisTenantVersionAllocatorTest {
    @Test void consumedVersionsNeverRewindAndReopenBurnsThePreviousRange() {
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative()) {
            var versions=allocator(f);var lease=f.lease(1);f.barrier.close(lease,1);
            var first=range(f,versions,1,1,100,109);versions.install(first);f.barrier.openPreparedGeneration(lease,1);
            assertEquals("100",write(f,versions,1,"100"));
            versions.install(first);assertEquals("101",write(f,versions,1,"101"));
            var delayed=f.barrier.captureOpen(f.key);
            var oldEvent=new TenantGenerationEnvelope<>("old external payload",delayed);
            assertThrows(RuntimeException.class,()->f.barrier.close(lease,1));
            var purge=f.lease(2);f.barrier.close(purge,1);
            assertThrows(RuntimeException.class,()->write(f,versions,1,"closed"));
            f.redis.del(f.keys.versionKey(f.ns,"id"),f.keys.entityKey(f.ns,"id"));
            f.barrier.installClosedGeneration(purge,2);
            var next=new TenantVersionRange(f.key,2,purge.epoch(),2,110,119,UUID.randomUUID(),versions.runId(f.key),ProtectionKey.hash("component fixture"));
            versions.install(next);f.barrier.openPreparedGeneration(purge,2);
            String owner=f.keys.versionKey(f.ns,"id")+":tenant",payload=f.keys.entityKey(f.ns,"id");
            assertThrows(RuntimeException.class,()->f.barrier.evalStamped(oldEvent,f.key,owner,"a",
                    "redis.call('SET',KEYS[3],'resurrected'); return 1",List.of(payload),List.of()));
            assertThrows(RuntimeException.class,()->f.barrier.evalOpen(delayed,owner,"a",
                    "redis.call('SET',KEYS[3],'late fill'); return 1",List.of(payload),List.of()));
            assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,
                    ()->f.barrier.evalStamped(null,f.key,owner,"a","return 1",List.of(),List.of())).code());
            assertNull(f.redis.get(payload));
            assertThrows(RuntimeException.class,()->versions.install(first));
            assertEquals("110",write(f,versions,2,"new"));
            assertEquals("110",f.redis.get(payload));assertEquals("a",f.redis.get(owner));
        }
    }
    @Test void exactMaximumExhaustionAndMissingCounterFailClosedWithoutFallback() {
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative()) {
            var versions=allocator(f);var lease=f.lease(1);f.barrier.close(lease,1);
            versions.install(range(f,versions,1,1,TenantVersionRange.MAX_VERSION-1,TenantVersionRange.MAX_VERSION));
            f.barrier.openPreparedGeneration(lease,1);
            assertEquals("9007199254740990",write(f,versions,1,"one"));
            assertEquals("9007199254740991",write(f,versions,1,"two"));
            assertThrows(RuntimeException.class,()->write(f,versions,1,"overflow"));
            assertEquals("9007199254740991",f.redis.get(versions.counterKey(f.key)));
            assertEquals("9007199254740991",f.redis.get(f.keys.entityKey(f.ns,"id")));
            f.redis.del(versions.counterKey(f.key));
            assertThrows(RuntimeException.class,()->write(f,versions,1,"missing"));
            assertEquals("CLOSED",f.redis.hget(f.barrier.generationKey(f.key),"state"));
            assertNull(f.redis.get(versions.counterKey(f.key)));
        }
    }
    @Test void replayingARetainedRangeCannotRepairAMissingCounterOrReopenAdmission() {
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative()) {
            var versions=allocator(f);var lease=f.lease(1);f.barrier.close(lease,1);
            var retained=range(f,versions,1,1,10,20);
            versions.install(retained);f.barrier.openPreparedGeneration(lease,1);
            assertEquals("10",write(f,versions,1,"first"));
            var allocatorState=f.redis.hgetAll(versions.allocatorKey(f.key));
            String owner=f.keys.versionKey(f.ns,"id")+":tenant";
            String version=f.redis.get(f.keys.versionKey(f.ns,"id"));
            String payload=f.redis.get(f.keys.entityKey(f.ns,"id"));
            f.redis.del(versions.counterKey(f.key));
            var failure=assertThrows(RuntimeException.class,()->versions.install(retained));
            assertTrue(failure.getMessage().contains("CDB_VERSION_RECOVERY_REQUIRED"));
            assertEquals("CLOSED",f.redis.hget(f.barrier.generationKey(f.key),"state"));
            assertNull(f.redis.get(versions.counterKey(f.key)));
            assertEquals(allocatorState,f.redis.hgetAll(versions.allocatorKey(f.key)));
            assertEquals(version,f.redis.get(f.keys.versionKey(f.ns,"id")));
            assertEquals(payload,f.redis.get(f.keys.entityKey(f.ns,"id")));assertEquals("a",f.redis.get(owner));
            assertThrows(RuntimeException.class,()->f.barrier.openPreparedGeneration(lease,1));
            assertNull(f.redis.get(versions.counterKey(f.key)));
            assertEquals("CLOSED",f.redis.hget(f.barrier.generationKey(f.key),"state"));
        }
    }
    @Test void processIdentityMismatchRejectsReadsAndMutationsBeforePayloadAccess() {
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative()) {
            var versions=allocator(f);var lease=f.lease(1);f.barrier.close(lease,1);
            versions.install(range(f,versions,1,1,10,20));f.barrier.openPreparedGeneration(lease,1);
            assertEquals("10",write(f,versions,1,"first"));
            f.redis.hset(versions.allocatorKey(f.key),"run_id","0".repeat(40));
            assertThrows(RuntimeException.class,()->f.barrier.evalOpen(new Generation(f.key,1),
                    f.keys.versionKey(f.ns,"id")+":tenant","a","return redis.call('GET',KEYS[3])",
                    List.of(f.keys.entityKey(f.ns,"id")),List.of()));
            assertEquals("CLOSED",f.redis.hget(f.barrier.generationKey(f.key),"state"));
            assertEquals("10",f.redis.get(f.keys.entityKey(f.ns,"id")));
            assertThrows(RuntimeException.class,()->write(f,versions,1,"stale"));
        }
    }
    @Test void coldAndDeletedVersionsAreInventoriedAndMissingPersistentVersionsRejectMigration() {
        try(var f=new RedisGenerationBarrierTest.Fixture();var admin=CacheTenantContext.administrative()) {
            var lease=f.lease(1);f.barrier.close(lease,1);
            String version=f.keys.versionKey(f.ns,"cold"),tombstone=f.keys.tombstoneKey(f.ns,"cold");
            f.redis.set(version,"90");f.redis.set(version+":tenant","a");f.redis.set(tombstone,"95");
            var inventory=new RedisVersionInventory(JedisPooledOperations.of(f.redis),f.keys,f.barrier,f.ns);
            assertEquals(90,inventory.maximum(lease,1,RedisPurgeScanner.Kind.OWNER,List.of(version+":tenant")));
            assertEquals(95,inventory.maximum(lease,1,RedisPurgeScanner.Kind.NAMESPACE,List.of(version,tombstone)));
            f.redis.del(version);
            assertThrows(RuntimeException.class,()->inventory.maximum(lease,1,RedisPurgeScanner.Kind.OWNER,List.of(version+":tenant")));
            assertEquals("95",f.redis.get(tombstone));assertEquals("a",f.redis.get(version+":tenant"));
        }
    }
    static RedisTenantVersionAllocator allocator(RedisGenerationBarrierTest.Fixture f) {
        return new RedisTenantVersionAllocator(JedisPooledOperations.of(f.redis),f.keys,f.barrier,f.ns);
    }
    static TenantVersionRange range(RedisGenerationBarrierTest.Fixture f,RedisTenantVersionAllocator a,
            long generation,long epoch,long first,long last) {
        return new TenantVersionRange(f.key,generation,1,epoch,first,last,UUID.randomUUID(),a.runId(f.key),ProtectionKey.hash("component fixture"));
    }
    static String write(RedisGenerationBarrierTest.Fixture f,RedisTenantVersionAllocator a,long generation,String ignored) {
        String owner=f.keys.versionKey(f.ns,"id")+":tenant";f.redis.set(owner,"a");
        return a.mutate(new Generation(f.key,generation),owner,"a",f.keys.versionKey(f.ns,"id"),"-1",
                List.of(f.keys.entityKey(f.ns,"id")),List.of(),"redis.call('SET',KEYS[6],version)");
    }
}
