package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.redis.RedisClientOperations;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class TypedSnapshotRepositoryTest {
    @Test void taggedNamespacesKeepPlansIndependentWithoutNormalizationCollisions() {
        String first = SnapshotJobs.snapshotPrefix("app:{shared}", "first");
        String second = SnapshotJobs.snapshotPrefix("app:{shared}", "second");
        assertThat(redis.clients.jedis.util.JedisClusterCRC16.getSlot(first))
                .isNotEqualTo(redis.clients.jedis.util.JedisClusterCRC16.getSlot(second));
        assertThat(SnapshotJobs.snapshotPrefix("a{b}c", "catalog"))
                .isNotEqualTo(SnapshotJobs.snapshotPrefix("ab{c}", "catalog"));
        assertThat(first).doesNotContain("{shared}");
    }
    record Summary(String title) {}

    @Test
    void fourStatesAndTypedValuesNeverNeedInfrastructure() {
        class MutableClock extends Clock {
            Instant now = Instant.parse("2026-10-21T00:00:00Z");

            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now;
            }
        }
        var clock = new MutableClock();
        var raw = new InMemorySnapshotRepository(clock, Duration.ofMinutes(1));
        TypedSnapshotRepository<String, Summary> typed =
                raw.typedRepository(new ObjectMapper(), Summary.class);
        assertThat(typed.lookup("1").status()).isEqualTo(SnapshotStatus.SNAPSHOT_UNAVAILABLE);
        raw.putSnapshot("1", "[{\"title\":\"hello\"}]");
        assertThat(typed.lookup("1").values()).containsExactly(new Summary("hello"));
        assertThat(typed.lookup("absent").status()).isEqualTo(SnapshotStatus.ABSENT_IN_GENERATION);
        clock.now = clock.now.plusSeconds(60);
        assertThat(typed.lookup("1").isStaleWarning()).isTrue();
        assertThat(typed.findValuesById("1")).contains(List.of(new Summary("hello")));
        assertThat(typed.lookupAll(List.of("1", "absent"))).hasSize(2);
        raw.expire();
        assertThat(typed.lookup("1").isSnapshotAvailable()).isFalse();
    }

    @Test
    void multiPointUsesOneEvalAndPreservesGenerationAndIdMapping() {
        var redis = mock(RedisClientOperations.class);
        String generation = UUID.randomUUID().toString();
        when(redis.eval(anyString(), anyList(), anyList()))
                .thenReturn(
                        List.of(
                                generation,
                                "1000",
                                "1",
                                "FOUND",
                                "1000",
                                "[1,2]",
                                "2",
                                "ABSENT_IN_GENERATION",
                                "1000",
                                ""));
        var raw =
                new RedisSnapshotRepository(
                        redis,
                        "test:{catalog}",
                        false,
                        null,
                        4096,
                        Clock.fixed(Instant.ofEpochMilli(1001), ZoneOffset.UTC),
                        Duration.ZERO);
        TypedSnapshotRepository<Long, Integer> typed =
                new DecodedSnapshotRepository<>(
                        raw, new JacksonJsonArrayCodec<>(new ObjectMapper(), Integer.class), Object::toString);
        var result = typed.lookupAll(List.of(1L, 2L));
        assertThat(result.get(1L).values()).containsExactly(1, 2);
        assertThat(result.get(2L).status()).isEqualTo(SnapshotStatus.ABSENT_IN_GENERATION);
        assertThat(result.values()).allMatch(value -> generation.equals(value.generation()));
        verify(redis, times(1)).eval(anyString(), anyList(), anyList());
        assertThatThrownBy(() -> typed.lookupAll(Collections.nCopies(1001, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroWarnAgeRetainsExistingDurationValidation() {
        var d = SnapshotSettings.defaults();
        assertThat(
                        new SnapshotSettings(
                                        d.enabled(),
                                        d.interval(),
                                        Duration.ZERO,
                                        d.maxAge(),
                                        d.retention(),
                                        d.timeout(),
                                        d.preparationTimeout(),
                                        d.leaseDuration(),
                                        d.batchRows(),
                                        d.fetchRows(),
                                        d.maxSourceRows(),
                                        d.maxRowsPerSource(),
                                        d.maxSourceSize(),
                                        d.batchTargetSize(),
                                        d.payloadWarningSize(),
                                        d.catalogWarningSize(),
                                        d.spoolDirectory())
                                .warnAge())
                .isZero();
    }
}
