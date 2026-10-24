package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.reactor.cachedb.core.repository.WindowRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotScoreRangeTest {
    @Test void rangesAreInclusiveAndContinuationKeepsBounds() {
        var raw = new InMemorySnapshotRepository();
        raw.putSnapshot("a", "[]", "stock", 1);
        raw.putSnapshot("b", "[]", "stock", 2);
        raw.putSnapshot("c", "[]", "stock", 2);
        raw.putSnapshot("d", "[]", "stock", 3);
        raw.putSnapshot("other", "[]", "other", 2);
        var page = raw.findPage(new SnapshotPageRequest("stock", 2, 3, WindowRequest.first(1)));
        assertThat(page.items()).extracting(SnapshotValue::id).containsExactly("b");
        var next = raw.findPage(new SnapshotPageRequest("stock", 2, 3, WindowRequest.after(page.nextCursor(), 3)));
        assertThat(next.items()).extracting(SnapshotValue::id).containsExactly("c", "d");
        assertThatThrownBy(() -> raw.findPage(new SnapshotPageRequest("stock", 1, 3, WindowRequest.after(page.nextCursor(), 3))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(raw.findPage(new SnapshotPageRequest("stock", 5, 6, WindowRequest.first(5))).items()).isEmpty();
    }
    @Test void validatesBoundsAndRetainsLegacyUnboundedAccess() {
        assertThatThrownBy(() -> new SnapshotPageRequest("stock", Double.NaN, 3, WindowRequest.first(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnapshotPageRequest("stock", 4, 3, WindowRequest.first(1)))
                .isInstanceOf(IllegalArgumentException.class);
        var raw = new InMemorySnapshotRepository();
        raw.putSnapshot("a", "[]", "stock", 1);
        assertThat(raw.findPage("stock", WindowRequest.first(10)).items()).hasSize(1);
        assertThat(new SnapshotTypedPageReadResult<String>(List.of(), null).page().items()).isEmpty();
    }
}
