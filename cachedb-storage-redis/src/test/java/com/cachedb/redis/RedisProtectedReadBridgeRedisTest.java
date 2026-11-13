package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.ProtectedReadFixture.*;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Component races use the real Redis barrier; they are not facade/source-deletion qualification. */
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(30)
class RedisProtectedReadBridgeRedisTest {
    static Lease open(RedisGenerationBarrierTest.Fixture f) {
        var lease=f.lease(1);
        try(var admin=CacheTenantContext.administrative()) {
            f.barrier.close(lease,1);
            var versions=new RedisTenantVersionAllocator(JedisPooledOperations.of(f.redis),f.keys,f.barrier,f.ns);
            versions.install(new TenantVersionRange(f.key,1,1,1,100,200,UUID.randomUUID(),
                    versions.runId(f.key),ProtectionKey.hash("read-fixture")));
            f.barrier.openPreparedGeneration(lease,1);
        }
        return lease;
    }
    static Lease close(RedisGenerationBarrierTest.Fixture f) {
        var lease=f.lease(2);
        try(var admin=CacheTenantContext.administrative()) {f.barrier.close(lease,1);}
        return lease;
    }
    static RedisTenantReadFence.Drain drain(RedisTenantReadFence fence,Lease lease) {
        try(var admin=CacheTenantContext.administrative()) {return fence.closeAndDrain(lease,1);}
    }
    static void owner(RedisGenerationBarrierTest.Fixture f) {
        f.redis.set(f.keys.versionKey(f.ns,"id")+":tenant","a");
        f.redis.set(f.keys.versionKey(f.ns,"id"),"150");
    }

    @Test void twoRuntimeL1CloseRejectsHeldDecodeAndRequiresBothAcknowledgements() throws Exception {
        var decoding=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var f=new RedisGenerationBarrierTest.Fixture()) {
            open(f);owner(f);var localA=store();var localB=store();
            try(var a=new RedisTenantReadFence(f.barrier,4,List.of(localA));
                    var b=new RedisTenantReadFence(f.barrier,4,List.of(localB));
                    var worker=Executors.newSingleThreadExecutor()) {
                for(var entry:List.of(Map.entry(a,localA),Map.entry(b,localB))) {
                    try(var ticket=entry.getKey().begin(f.key)) {
                        entry.getKey().cache(ticket,entry.getValue(),"id",CODEC.toRedisValue(RedisProtectedPointReadsTest.ROW),
                                10000,150,entry.getValue().capture("id"));
                        ticket.complete(null);
                    }
                }
                var reads=new RedisProtectedPointReads<>(a,metadata(f.ns),CODEC,f.keys,localA,
                        row->{decoding.countDown();await(release);return true;},null,null,Set.of());
                Future<?> pending=worker.submit(()->reads.findById("a","id",0));
                await(decoding);var closed=close(f);
                assertFalse(drain(a,closed).drained());
                assertTrue(drain(b,closed).drained());
                assertNull(localA.get("id"));assertNull(localB.get("id"));
                release.countDown();
                assertInstanceOf(ProtectionException.class,assertThrows(ExecutionException.class,
                        ()->pending.get(5,TimeUnit.SECONDS)).getCause());
                assertTrue(drain(a,closed).drained());
            } finally {release.countDown();}
        }
    }

    @Test void delayedSourceFillNeverRecreatesPayloadAfterRealBarrierClose() throws Exception {
        var loading=new CountDownLatch(1);var release=new CountDownLatch(1);var planned=new AtomicInteger();
        try(var f=new RedisGenerationBarrierTest.Fixture()) {
            open(f);owner(f);
            try(var fence=new RedisTenantReadFence(f.barrier,4,List.of());var worker=Executors.newSingleThreadExecutor()) {
                var row=RedisProtectedPointReadsTest.ROW;
                var reads=new RedisProtectedPointReads<>(fence,metadata(f.ns),CODEC,f.keys,null,entity->true,
                        (original,id)->{loading.countDown();await(release);return Optional.of(new VersionedEntity<>(row,150));},
                        (original,entity,version)->{
                            planned.incrementAndGet();
                            return new RedisTenantReadFence.Publication(original,f.keys.versionKey(f.ns,"id")+":tenant","a",
                                    "return redis.call('SET',KEYS[3],ARGV[3])",
                                    List.of(f.keys.entityKey(f.ns,"id")),List.of(CODEC.toRedisValue(entity)));
                        },Set.of());
                Future<?> pending=worker.submit(()->reads.findById("a","id",0));
                await(loading);var closed=close(f);
                assertFalse(drain(fence,closed).drained());
                release.countDown();
                assertInstanceOf(ProtectionException.class,assertThrows(ExecutionException.class,
                        ()->pending.get(5,TimeUnit.SECONDS)).getCause());
                assertEquals(0,planned.get());
                assertNull(f.redis.get(f.keys.entityKey(f.ns,"id")));
                assertTrue(drain(fence,closed).drained());
                assertEquals("a",f.redis.get(f.keys.versionKey(f.ns,"id")+":tenant"));
            } finally {release.countDown();}
        }
    }

    @Test void reviewedPublicationIsAtomicAndClosedPublicationCannotMutate() {
        try(var f=new RedisGenerationBarrierTest.Fixture()) {
            open(f);owner(f);
            try(var fence=new RedisTenantReadFence(f.barrier,4,List.of());var ticket=fence.begin(f.key)) {
                String target=f.keys.entityKey(f.ns,"fill");
                var plan=new RedisTenantReadFence.Publication(ticket.generation(),
                        f.keys.versionKey(f.ns,"id")+":tenant","a",
                        "return redis.call('SET',KEYS[3],ARGV[3])",List.of(target),List.of("protected_payload"));
                ticket.publish(plan);assertEquals("protected_payload",f.redis.get(target));
                var closed=close(f);
                assertThrows(ProtectionException.class,()->ticket.publish(plan));
                assertFalse(drain(fence,closed).drained());
                assertEquals("protected_payload",f.redis.get(target)); // No erasure is claimed here.
            }
        }
    }

    @Test void unknownProjectionRemainsIncompleteWithoutReadingOrClaimingSpringCacheOwnership() {
        try(var f=new RedisGenerationBarrierTest.Fixture()) {
            open(f);owner(f);
            try(var fence=new RedisTenantReadFence(f.barrier,4,List.of())) {
                var reads=new RedisProtectedPointReads<>(fence,metadata(f.ns),CODEC,f.keys,null,row->true,null,null,Set.of());
                var projection=RedisProtectedPointReadsTest.projection("not_reviewed",java.util.function.Function.identity());
                assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY,
                        assertThrows(ProtectionException.class,()->reads.projected("a",projection)).code());
            }
        }
    }

    @Test void exactFacadeSpiWaitsForEveryRuntimeAndReplacementLeaseCannotReuseEvidence() {
        try(var f=new RedisGenerationBarrierTest.Fixture();
                var a=new RedisTenantReadFence(f.barrier,4,List.of(store()));
                var b=new RedisTenantReadFence(f.barrier,4,List.of(store()))) {
            open(f);var lease=close(f);
            String manifest=ProtectionKey.hash("approved-complete-fixture-manifest");
            var roster=new RedisNativeTenantReadFence.RuntimeManifest(manifest,List.of(a.runtimeId(),b.runtimeId()),true);
            RedisNativeTenantReadFence.NativeClosureProbe probe=(l,g,m)->new RedisNativeTenantReadFence.Probe(
                    TenantReadFence.State.CLOSED,ProtectionKey.hash("fixture-producers-stores-and-topology"));
            var left=new RedisNativeTenantReadFence(JedisPooledOperations.of(f.redis),f.barrier,f.key,roster,a,probe,10000);
            var right=new RedisNativeTenantReadFence(JedisPooledOperations.of(f.redis),f.barrier,f.key,roster,b,probe,10000);
            try(var admin=CacheTenantContext.administrative()) {
                var pending=left.closeAndDrain(lease,1,manifest,"",128,5000);
                assertEquals(TenantReadFence.State.DRAINING,pending.state());
                assertEquals(2,pending.registered());assertEquals(1,pending.acknowledged());
                assertEquals(TenantReadFence.State.CLOSED,right.poll(5000));
                var closed=left.closeAndDrain(lease,1,manifest,pending.checkpoint(),128,5000);
                assertEquals(TenantReadFence.State.CLOSED,closed.state());
                left.requireClosed(lease,1,manifest,closed);
                right.requireClosed(lease,1,manifest,closed);
                var replacement=f.lease(3);f.barrier.close(replacement,1);
                assertThrows(ProtectionException.class,()->left.requireClosed(lease,1,manifest,closed));
                var reset=left.closeAndDrain(replacement,1,manifest,closed.checkpoint(),128,5000);
                assertEquals(TenantReadFence.State.DRAINING,reset.state());
                assertThrows(ProtectionException.class,()->left.requireClosed(replacement,1,manifest,closed));
                right.poll(5000);
                var fresh=left.closeAndDrain(replacement,1,manifest,reset.checkpoint(),128,5000);
                assertEquals(TenantReadFence.State.CLOSED,fresh.state());
                assertNotEquals(closed.token(),fresh.token());
                left.requireClosed(replacement,1,manifest,fresh);
                left.release(replacement);
                assertEquals("CLOSED",f.redis.hget(f.barrier.generationKey(f.key),"state"));
                assertNotNull(f.redis.get(f.barrier.leaseKey(f.key)));
            }
        }
    }

    @Test void emptyUnknownMembershipOrAbsentProducerAndStoreProofNeverCloses() {
        try(var f=new RedisGenerationBarrierTest.Fixture();
                var local=new RedisTenantReadFence(f.barrier,4,List.of())) {
            open(f);var lease=close(f);String token=ProtectionKey.hash("incomplete-fixture");
            var empty=new RedisNativeTenantReadFence.RuntimeManifest(token,List.of(),true);
            var noRuntime=new RedisNativeTenantReadFence(JedisPooledOperations.of(f.redis),f.barrier,f.key,empty,local,null,10000);
            var required=new RedisNativeTenantReadFence.RuntimeManifest(token,List.of(local.runtimeId()),true);
            var noProducer=new RedisNativeTenantReadFence(JedisPooledOperations.of(f.redis),f.barrier,f.key,required,local,null,10000);
            var unknown=new RedisNativeTenantReadFence(JedisPooledOperations.of(f.redis),f.barrier,f.key,
                    new RedisNativeTenantReadFence.RuntimeManifest(token,List.of(local.runtimeId()),false),local,
                    (l,g,m)->{throw new AssertionError("Unknown inventory must not probe");},10000);
            try(var admin=CacheTenantContext.administrative()) {
                for(var fence:List.of(noRuntime,noProducer,unknown))
                    assertEquals(TenantReadFence.State.INCOMPLETE,fence.closeAndDrain(lease,1,token,"",128,5000).state());
            }
        }
    }

    @Test void staleAcknowledgementAndUnknownJournalSchemaRejectWithoutErasingForeignState() {
        try(var f=new RedisGenerationBarrierTest.Fixture();
                var a=new RedisTenantReadFence(f.barrier,4,List.of());
                var b=new RedisTenantReadFence(f.barrier,4,List.of())) {
            open(f);var lease=close(f);String token=ProtectionKey.hash("live-fixture");
            var roster=new RedisNativeTenantReadFence.RuntimeManifest(token,List.of(a.runtimeId(),b.runtimeId()),true);
            RedisNativeTenantReadFence.NativeClosureProbe probe=(l,g,m)->new RedisNativeTenantReadFence.Probe(
                    TenantReadFence.State.CLOSED,ProtectionKey.hash("fixture-reviewed-owned-stores"));
            var left=new RedisNativeTenantReadFence(JedisPooledOperations.of(f.redis),f.barrier,f.key,roster,a,probe,10000);
            var right=new RedisNativeTenantReadFence(JedisPooledOperations.of(f.redis),f.barrier,f.key,roster,b,probe,10000);
            String journal=f.barrier.generationKey(f.key)+":read-closure";
            try(var admin=CacheTenantContext.administrative()) {
                left.closeAndDrain(lease,1,token,"",128,5000);right.acknowledge(lease,1,token,5000);
                var closed=left.closeAndDrain(lease,1,token,"read-v1",128,5000);
                assertEquals(TenantReadFence.State.CLOSED,closed.state());
                f.redis.hset(journal,"t:"+b.runtimeId(),"0");
                assertThrows(ProtectionException.class,()->left.requireClosed(lease,1,token,closed));
                f.redis.hset(journal,"foreign-owned-field","preserve");
                assertThrows(ProtectionException.class,()->left.closeAndDrain(lease,1,token,"read-v1",128,5000));
                assertEquals("preserve",f.redis.hget(journal,"foreign-owned-field"));
            }
        }
    }
}
