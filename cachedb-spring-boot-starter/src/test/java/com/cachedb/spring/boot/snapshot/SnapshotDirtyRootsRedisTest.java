package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.reactor.cachedb.redis.JedisPooledOperations;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
class SnapshotDirtyRootsRedisTest {
    @Test
    @EnabledIfSystemProperty(named="cachedb.redis.cluster", matches="true")
    void untaggedPlanPrefixesStillUseSameSlotDirtyReservations() {
        try (var cluster = com.reactor.cachedb.spring.boot.TestFixtureSettings.cluster()) {
            var dirty = new SnapshotDirtyRoots(cluster,"dirty-cluster-"+UUID.randomUUID(),SnapshotSettings.defaults());
            assertThat(dirty.mark(List.of("one","two"))).isEqualTo(2);
            assertThat(dirty.flush("catalog",ids -> {
                assertThat(ids).containsExactlyInAnyOrder("one","two");
                return new SnapshotRefreshResult("COMPLETED",ids.size(),ids.size(),1);
            })).isPresent();
            assertThat(dirty.flush("catalog",ids -> null)).isEmpty();
        }
    }

    @Test void boundedReservationsRequeueFailuresAndRetainUpdatesDuringRefresh() {
        String prefix = "dirty-test-"+UUID.randomUUID()+":{roots}";
        try (var redis = new JedisPooled(System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port",16383))) {
            var dirty = new SnapshotDirtyRoots(JedisPooledOperations.of(redis), prefix,
                    SnapshotContractTest.settings(Map.of("batch-rows",3)));
            assertThat(dirty.mark(List.of("a","a","b"))).isEqualTo(2);
            assertThatThrownBy(() -> dirty.mark(List.of("c","d"))).isInstanceOf(IllegalStateException.class);
            assertThat(redis.smembers(prefix+":dirty")).containsExactlyInAnyOrder("a","b");
            assertThatThrownBy(() -> dirty.flush("catalog", ids -> { throw new IllegalStateException("SQL unavailable"); }))
                    .isInstanceOf(IllegalStateException.class).hasMessage("SQL unavailable");
            assertThat(redis.smembers(prefix+":dirty")).containsExactlyInAnyOrder("a","b");
            var manifest = dirty.flush("catalog", ids -> {
                assertThat(ids).containsExactlyInAnyOrder("a","b");
                assertThatThrownBy(() -> dirty.flush("catalog", ignored -> null)).isInstanceOf(IllegalStateException.class);
                assertThat(dirty.mark(List.of("a","c"))).isEqualTo(2);
                assertThatThrownBy(() -> dirty.mark(List.of("d"))).isInstanceOf(IllegalStateException.class);
                return new SnapshotRefreshResult("COMPLETED",2,2,1);
            }).orElseThrow();
            assertThat(manifest.rootCount()).isEqualTo(2);
            assertThat(redis.smembers(prefix+":dirty")).containsExactlyInAnyOrder("a","c");
            assertThat(redis.exists(prefix+":dirty-inflight")).isFalse();
            dirty.flush("catalog", ids -> new SnapshotRefreshResult("COMPLETED",ids.size(),ids.size(),1));
            assertThat(dirty.flush("catalog", ignored -> null)).isEmpty();
        }
    }
    @Test void expiredClaimRecoversReservedRoots() {
        String prefix = "dirty-test-"+UUID.randomUUID()+":{roots}";
        try (var redis = new JedisPooled(System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"),
                Integer.getInteger("cachedb.snapshot.redis.port",16383))) {
            redis.sadd(prefix+":dirty-inflight","orphan");
            var dirty = new SnapshotDirtyRoots(JedisPooledOperations.of(redis), prefix, SnapshotSettings.defaults());
            assertThat(dirty.flush("catalog", ids -> {
                assertThat(ids).containsExactly("orphan");
                return new SnapshotRefreshResult("COMPLETED",1,1,1);
            })).isPresent();
            assertThat(redis.exists(prefix+":dirty-inflight")).isFalse();
        }
    }
}
