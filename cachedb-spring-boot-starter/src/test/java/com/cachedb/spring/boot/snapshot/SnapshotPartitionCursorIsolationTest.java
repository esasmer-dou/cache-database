package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.reactor.cachedb.core.repository.WindowRequest;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class SnapshotPartitionCursorIsolationTest {
    private final SnapshotIndexDeltaRedisTest fixture = new SnapshotIndexDeltaRedisTest();

    @BeforeEach
    void setup() throws Exception {
        fixture.setup();
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void deltaInADoesNotInvalidateBButMovingIntoBDoes() throws Exception {
        fixture.execute(
                "INSERT INTO \"records\" VALUES ('d','other',1,'four'),('e','other',1,'five')");
        fixture.jobs.refresh("catalog", true);
        var repository = fixture.jobs.repository("catalog");
        var first = repository.findPage("other", WindowRequest.first(1));
        assertThat(first.items()).extracting(SnapshotValue::id).containsExactly("d");
        fixture.execute("UPDATE \"records\" SET \"value\"='changed' WHERE \"id\"='a'");
        repository.refreshRoots(List.of("a"));
        assertThat(repository.findPage("other", first.nextRequest(1).orElseThrow()).items())
                .extracting(SnapshotValue::id)
                .containsExactly("e");
        fixture.execute("UPDATE \"records\" SET \"partition\"='other' WHERE \"id\"='a'");
        repository.refreshRoots(List.of("a"));
        assertThatThrownBy(() -> repository.findPage("other", first.nextRequest(1).orElseThrow()))
                .hasMessageContaining("cursor expired");
        assertThat(repository.findByIds(List.of("a", "e", "missing"))).containsOnlyKeys("a", "e");
    }
}
