package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.core.repository.WindowRequest;
import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotPlanDeclarativeApiTest {
    @Test void scopeShortcutsBindTypedReadsAndExplicitPartialRefresh() {
        var source = SnapshotSource.entity("roots", new SourceMapping<String>("roots", List.of("id"),
                row -> row.get("id").toString()));
        var codec = new JacksonFreeSnapshotTest.SummaryCodec();
        var plan = SnapshotPlan.streaming("catalog", source, value -> value,
                JacksonFreeSnapshotTest.Summary.class, value -> List.of(new JacksonFreeSnapshotTest.Summary(value)))
                .scopedBy(source, "id", Long::valueOf).withJsonCodec(codec);
        assertThat(plan.rootScope().narrow(source, List.of("1","2")).command("H2").parameters()).containsExactly(1L,2L);
        assertThatThrownBy(() -> plan.rootScope().narrow(source, List.of("not-a-number")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan.scopedByString(source,"missing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(plan.scopedByString(source,"id").rootScope().narrow(source,List.of("a")).command("H2").parameters())
                .containsExactly("a");
        var raw = new InMemorySnapshotRepository().onRefresh(ids -> Map.of("1","[{\"title\":\"updated\"}]"));
        raw.putSnapshot("1","[{\"title\":\"initial\"}]","books",2);
        TypedSnapshotRepository<Long, JacksonFreeSnapshotTest.Summary> typed = plan.bind(raw);
        assertThat(typed.lookup(1L).values()).containsExactly(new JacksonFreeSnapshotTest.Summary("initial"));
        assertThat(typed.findTypedPage(new SnapshotPageRequest("books",1,3,WindowRequest.first(1))).items()).hasSize(1);
        typed.refreshRoots(List.of(1L));
        assertThat(typed.lookup(1L).values()).containsExactly(new JacksonFreeSnapshotTest.Summary("updated"));
        assertThatThrownBy(plan::withGeneratedJsonCodec).isInstanceOf(IllegalArgumentException.class);
    }
}
