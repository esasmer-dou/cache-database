package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

class SnapshotStreamingTest {
    @TempDir Path directory;

    @Test
    void explicitStreamingSpoolsRowsWithoutAnAggregateRootListOrMutableRowReuse() throws Exception {
        var sql = new JdbcDataSource();
        sql.setURL("jdbc:h2:mem:stream-" + UUID.randomUUID());
        try (var connection = sql.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE roots AS SELECT X AS ID, 'row-' || X AS NAME FROM"
                            + " SYSTEM_RANGE(1,3000)");
            List<Map<String, Object>> retainedRows = new ArrayList<>();
            var roots =
                    new SnapshotSource<Map<String, Object>>(
                            "roots",
                            "SELECT ID, NAME FROM roots ORDER BY ID",
                            row -> {
                                retainedRows.add(row);
                                return row;
                            });
            var plan =
                    SnapshotPlan.streaming(
                            "catalog",
                            roots,
                            row -> row.get("ID").toString(),
                            String.class,
                            row -> List.of(row.get("NAME").toString()));
            assertThat(plan.streaming()).isTrue();
            var mapper = new ObjectMapper();
            try (var spool = new SnapshotSpool(directory)) {
                spool.prepareStreaming(
                        sql, plan, SnapshotSettings.defaults(), mapper, () -> {}, List.of());
                assertThat(spool.entries()).hasSize(3000);
                assertThat(spool.read(spool.entries().get(0), () -> {})).isEqualTo("[\"row-1\"]");
                assertThat(spool.read(spool.entries().get(2999), () -> {}))
                        .isEqualTo("[\"row-3000\"]");
            }
            assertThat(retainedRows.get(0).get("ID")).isEqualTo(1L);
            assertThat(retainedRows.get(2999).get("ID")).isEqualTo(3000L);
            assertThat(
                            new SnapshotPlan<>(
                                            "old",
                                            roots,
                                            row -> "1",
                                            List.of(roots),
                                            String.class,
                                            rows -> row -> List.of("complete"))
                                    .streaming())
                    .isFalse();
        }
        assertThat(directory.toFile().list()).isEmpty();
    }
}
