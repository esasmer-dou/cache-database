package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.UnifiedJedis;

class RedisCausalReadsTest {
    @Test
    @EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
    @EnabledIfSystemProperty(named="cachedb.redis.cluster",matches="true")
    void liveClusterReadsCoLocatedAndSplitTriplesWithoutCrossSlotCommands() {
        var config = redis.clients.jedis.DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                .hostAndPortMapper(address -> System.getProperty("cachedb.redis.cluster.host") != null ? address
                        : new redis.clients.jedis.HostAndPort("127.0.0.1",address.getPort()<10000 ? address.getPort()+10000 : address.getPort()))
                .build();
        try (var redis = new JedisClusterOperations(new redis.clients.jedis.JedisCluster(Set.of(
                new redis.clients.jedis.HostAndPort(System.getProperty("cachedb.redis.cluster.host","127.0.0.1"),
                        Integer.getInteger("cachedb.redis.cluster.port",17001))),config))) {
            String prefix = "causal-"+UUID.randomUUID();
            for (boolean split : new boolean[]{false,true}) {
                var entities = new ArrayList<String>(); var tombstones = new ArrayList<String>(); var versions = new ArrayList<String>();
                for (int i=0;i<3;i++) {
                    entities.add(prefix+":{entity"+i+"}:payload");
                    tombstones.add(prefix+":{"+(split ? "deleted" : "entity")+i+"}:tombstone");
                    versions.add(prefix+":{"+(split ? "version" : "entity")+i+"}:version");
                }
                try {
                    for (int i=0;i<3;i++) { redis.commands().set(entities.get(i),"row"+i); redis.commands().set(versions.get(i),"8"); }
                    assertEquals(List.of("row0","row1","row2"),RedisCausalReads.read(redis,entities,tombstones,versions,
                            (i,v) -> assertEquals("8",v)).subList(0,3));
                } finally {
                    var all = new ArrayList<String>(); all.addAll(entities); all.addAll(tombstones); all.addAll(versions);
                    redis.delete(all.toArray(String[]::new));
                }
            }
        }
    }
    private static final class Client implements RedisClientOperations {
        final Map<String,String> data = new HashMap<>();
        final List<List<String>> calls = new ArrayList<>();
        public UnifiedJedis commands() { throw new AssertionError("Use grouped MGET"); }
        public boolean clustered() { return true; }
        public List<String> mget(String... keys) {
            requireSameSlot(List.of(keys)); calls.add(List.of(keys));
            return Arrays.stream(keys).map(data::get).toList();
        }
    }
    @Test void splitTriplesReadVersionsFirstAndKeepInputOrder() {
        var client = new Client();
        var entities = List.of("{payload}:1","{payload}:2","{payload}:3");
        var deleted = List.of("{deleted}:1","{deleted}:2","{deleted}:3");
        var versions = List.of("{version}:1","{version}:2","{version}:3");
        for (int i = 0; i < 3; i++) { client.data.put(entities.get(i),"row"+i); client.data.put(versions.get(i),"7"); }
        var values = RedisCausalReads.read(client,entities,deleted,versions,(index,version) -> {
            assertEquals("7",version); assertTrue(client.calls.stream().flatMap(List::stream).noneMatch(entities::contains));
        });
        assertEquals(List.of("row0","row1","row2"),values.subList(0,3));
        assertEquals(3,client.calls.size()); assertEquals(versions,client.calls.get(0));
    }
    @Test void sameSlotTriplesUseOneReadPerSlotAndStaleSplitVersionFailsBeforePayload() {
        var client = new Client();
        var entities = List.of("{a}:entity","{b}:entity","{c}:entity");
        var deleted = List.of("{a}:deleted","{b}:deleted","{c}:deleted");
        var versions = List.of("{a}:version","{b}:version","{c}:version");
        for (int i = 0; i < 3; i++) { client.data.put(entities.get(i),"row"+i); client.data.put(versions.get(i),"8"); }
        assertEquals(9,RedisCausalReads.read(client,entities,deleted,versions,(i,v) -> assertEquals("8",v)).size());
        assertEquals(3,client.calls.size());
        client.calls.clear(); client.data.put("{version}:1","1");
        assertThrows(IllegalStateException.class,() -> RedisCausalReads.read(client,List.of("{payload}:1"),List.of("{deleted}:1"),List.of("{version}:1"),
                (i,v) -> { if (Long.parseLong(v) < 2) throw new IllegalStateException("Causal version is not available"); }));
        assertEquals(List.of(List.of("{version}:1")),client.calls);
    }
    @Test void batchesLargerThan512AreRejectedBeforeRedis() {
        var keys = Collections.nCopies(513,"key");
        assertThrows(IllegalArgumentException.class,() -> RedisCausalReads.read(new Client(),keys,keys,keys,(i,v) -> {}));
    }
}
