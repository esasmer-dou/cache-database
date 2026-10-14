package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.reactor.cachedb.core.model.SourceMapping;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.IntStream;

class SnapshotExtendedApiTest {
    @Test
    void operatorsBindValuesAndOracleChunksLists() {
        var source =
                SnapshotSource.entity(
                        "rows", new SourceMapping<>("rows", List.of("id", "state"), row -> row));
        var predicate =
                SnapshotPredicate.ne("state", null)
                        .and(SnapshotPredicate.gte("id", 1))
                        .and(SnapshotPredicate.lte("id", 10))
                        .and(SnapshotPredicate.between("id", 2, 9))
                        .and(SnapshotPredicate.inValues("state", List.of("ACTIVE", "PENDING")));
        for (String product : List.of("H2", "PostgreSQL", "Microsoft SQL Server", "Oracle")) {
            var command = source.where(predicate).command(product);
            assertThat(command.parameters()).containsExactly(1, 10, 2, 9, "ACTIVE", "PENDING");
            assertThat(command.sql()).contains("IS NOT NULL", ">= ?", "<= ?", "BETWEEN ? AND ?");
        }
        var command =
                source.where(
                                SnapshotPredicate.inValues(
                                        "id", IntStream.range(0, 1801).boxed().toList()))
                        .command("Oracle");
        assertThat(command.parameters()).hasSize(1801);
        assertThat(command.sql().split(" OR ", -1)).hasSize(3);
        assertThatThrownBy(() -> SnapshotPredicate.inValues("id", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SnapshotPredicate.gt("id", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void typedRelationsReverseTheSameReadAndPreserveDuplicates() {
        var links = SnapshotRelation.longs("links", "links", "parent", "child");
        var rows =
                new SnapshotRows(
                        Map.of(
                                links.source(),
                                List.of(
                                        new TypedSnapshotRelation.Edge<>(1L, 2L),
                                        new TypedSnapshotRelation.Edge<>(1L, 2L))));
        assertThat(links.reverse().source()).isSameAs(links.source());
        assertThat(rows.lists(links).get(1L)).containsExactly(2L, 2L);
        assertThat(rows.membership(links.reverse()).containsAll(2L, List.of(1L))).isTrue();
        var uuidLinks = SnapshotRelation.uuids("uuids", "uuids", "a", "b");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var uuidRows =
                new SnapshotRows(
                        Map.of(
                                uuidLinks.source(),
                                List.of(new TypedSnapshotRelation.Edge<>(a, b))));
        assertThat(uuidRows.lists(uuidLinks.reverse()).get(b)).containsExactly(a);
    }
}
