package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(60)
class RedisGenerationBarrierTest {
    @Test void atomicRedisGuardRejectsClosedAndOldGenerationsWithoutForegroundSql() {
        try(var f=new Fixture();var admin=CacheTenantContext.administrative()) {
            var first=f.lease(1);f.barrier.close(first,1);
            String owner=f.keys.versionKey(f.ns,"id")+":tenant",counter=f.keys.entityKey(f.ns,"counter");
            f.redis.set(owner,"a");
            assertThrows(RuntimeException.class,()->f.barrier.evalOpen(new Generation(f.key,1),owner,"a",
                    "return redis.call('INCR',KEYS[3])",List.of(counter),List.of()));
            f.barrier.installClosedGeneration(first,2);
            assertThrows(RuntimeException.class,()->f.barrier.openPreparedGeneration(first,2));
            var versions=new RedisTenantVersionAllocator(JedisPooledOperations.of(f.redis),f.keys,f.barrier,f.ns);
            versions.install(new TenantVersionRange(f.key,2,1,1,100,200,UUID.randomUUID(),versions.runId(f.key),ProtectionKey.hash("fixture")));
            // Component fixture: production also requires the already committed SQL OPEN.
            f.barrier.openPreparedGeneration(first,2);
            assertThrows(RuntimeException.class,()->f.barrier.evalOpen(new Generation(f.key,1),owner,"a",
                    "return redis.call('INCR',KEYS[3])",List.of(counter),List.of()));
            assertEquals(1L,((Number)f.barrier.evalOpen(new Generation(f.key,2),owner,"a",
                    "return redis.call('INCR',KEYS[3])",List.of(counter),List.of())).longValue());
            assertEquals("a",f.redis.get(owner));
        }
    }
    @Test void takeoverFencesOldOwnershipAndOtherPairRemainsOpen() {
        try(var f=new Fixture();var admin=CacheTenantContext.administrative()) {
            var old=f.lease(1);f.barrier.close(old,1);
            var peer=f.barrier.key("b");f.redis.hset(f.barrier.generationKey(peer),Map.of("generation","9","state","OPEN"));
            var current=f.lease(2);f.barrier.close(current,1);
            assertThrows(RuntimeException.class,()->f.barrier.assertClosed(old,1));
            f.barrier.release(old);f.barrier.assertClosed(current,1);
            assertEquals("OPEN",f.redis.hget(f.barrier.generationKey(peer),"state"));
            assertNotEquals(f.key.tenantToken(),peer.tenantToken());
        }
    }
    @Test void coldOwnerMarkersAndUnknownLegacyOrphansAreDiscoveredWithoutHotIndexCoverageClaims() {
        try(var f=new Fixture();var admin=CacheTenantContext.administrative();
                var wire=new BoundedRedisWire(new InetSocketAddress(System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),
                        Integer.getInteger("cachedb.snapshot.redis.port",16383)),null,null,null,null,0,3000,5000,1)) {
            var lease=f.lease(1);f.barrier.close(lease,1);
            f.redis.set(f.keys.versionKey(f.ns,"cold"),"99");
            f.redis.set(f.keys.versionKey(f.ns,"cold")+":tenant","a");
            f.redis.set(f.keys.tombstoneKey(f.ns,"cold"),"99");
            f.redis.set(f.keys.compactionPendingKey(f.ns,"cold"),"99");
            f.redis.set(f.keys.entityKey(f.ns,"legacy"),"unknown-owner");
            f.redis.set(f.keys.entityKey(f.ns,"foreign"),"foreign");
            f.redis.set(f.keys.versionKey(f.ns,"foreign")+":tenant","b");
            var scanner=new RedisPurgeScanner(wire,JedisPooledOperations.of(f.redis),f.keys,f.barrier,f.ns);
            String topology=scanner.captureTopology();var owned=new HashSet<String>();int unknown=0;
            for(var kind:List.of(RedisPurgeScanner.Kind.OWNER,RedisPurgeScanner.Kind.NAMESPACE)) {
                String cursor="0";int pages=0;
                do {
                    assertTrue(++pages<=1000);
                    var frame=scanner.scan(lease,1,kind,null,cursor,topology);
                    for(int offset=0;offset<frame.items().size();offset+=128) {
                        var resolved=scanner.resolve(lease,1,kind,frame.items().subList(offset,Math.min(offset+128,frame.items().size())),"a");
                        owned.addAll(resolved.ownedIds());unknown+=resolved.unknown();
                        assertFalse(resolved.toString().contains("foreign"));
                    }
                    cursor=frame.nextCursor();
                } while(!cursor.equals("0"));
            }
            assertTrue(owned.contains("cold"));assertFalse(owned.contains("foreign"));assertTrue(unknown>0);
            assertNull(f.redis.get(f.keys.entityKey(f.ns,"cold"))); // Cold identity was never resident.
            assertEquals("foreign",f.redis.get(f.keys.entityKey(f.ns,"foreign")));
            assertEquals("unknown-owner",f.redis.get(f.keys.entityKey(f.ns,"legacy")));
        }
    }
    static final class Fixture implements AutoCloseable {
        final String ns="purge_"+UUID.randomUUID().toString().replace("-","");
        final RedisKeyStrategy keys=new RedisKeyStrategy("f3");
        final JedisPooled redis=new JedisPooled(RedisTestEndpoint.uri());
        final RedisGenerationBarrier barrier=new RedisGenerationBarrier(JedisPooledOperations.of(redis),keys,ns);
        final ProtectionKey key=barrier.key("a");final UUID operation=UUID.randomUUID();
        Lease lease(long epoch){return new Lease(key,operation,UUID.randomUUID(),epoch,120);}
        public void close() {
            try {
                String cursor="0";int pages=0;
                do {
                    if(++pages>1000)throw new IllegalStateException("Fixture cleanup bound");
                    var page=redis.scan(cursor,new redis.clients.jedis.params.ScanParams().match("f3:"+ns+":*").count(128));
                    cursor=page.getCursor();if(!page.getResult().isEmpty())redis.del(page.getResult().toArray(String[]::new));
                }while(!cursor.equals("0"));
            }finally{redis.close();}
        }
    }
}
