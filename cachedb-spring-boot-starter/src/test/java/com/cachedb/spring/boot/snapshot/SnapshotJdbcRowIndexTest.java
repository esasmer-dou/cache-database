package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

class SnapshotJdbcRowIndexTest {
    @Test
    void sharedColumnIndexKeepsCaseNullsRetainedRowsOrderAndImmutability() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:row_index_" + UUID.randomUUID());
        try (var owner = data.getConnection()) {
            var source =
                    new SnapshotSource<Map<String, Object>>(
                            "rows",
                            "SELECT X AS ID, X + 1 AS NEXT_ID, CAST(NULL AS VARCHAR) AS NOTE FROM"
                                + " SYSTEM_RANGE(1,3) ORDER BY X",
                            row -> row);
            var rows =
                    SnapshotJdbcReader.read(
                                    data, plan(source), SnapshotSettings.defaults(), () -> {})
                            .get(source);
            assertEquals(3, rows.size());
            var row = rows.get(0);
            assertEquals(1L, row.get("ID"));
            assertEquals(2L, row.get("NEXT_ID"));
            assertNull(row.get("NOTE"));
            assertTrue(row.containsKey("NOTE"));
            assertFalse(row.containsKey("note"));
            assertNull(row.get("id"));
            assertNull(row.get(null));
            Object asymmetric =
                    new Object() {
                        public int hashCode() {
                            return "ID".hashCode();
                        }

                        public boolean equals(Object other) {
                            return "ID".equals(other);
                        }
                    };
            assertNull(row.get(asymmetric));
            assertFalse(row.containsKey(asymmetric));
            assertEquals(List.of("ID", "NEXT_ID", "NOTE"), List.copyOf(row.keySet()));
            assertThrows(UnsupportedOperationException.class, () -> row.put("ID", 7L));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> row.entrySet().iterator().next().setValue(7L));
            assertEquals(3L, rows.get(2).get("ID"));
            assertEquals(1L, row.get("ID"));
        }
    }

    @Test
    void duplicateLabelsKeepTheFirstLookupAndExistingEntryOrder() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:duplicate_label_" + UUID.randomUUID());
        try (var owner = data.getConnection()) {
            var source =
                    new SnapshotSource<Map<String, Object>>(
                            "rows", "SELECT 1 AS ID, 2 AS ID", row -> row);
            var row =
                    SnapshotJdbcReader.read(
                                    data, plan(source), SnapshotSettings.defaults(), () -> {})
                            .get(source)
                            .get(0);
            assertEquals(1, row.get("ID"));
            assertEquals(List.of(1, 2), row.entrySet().stream().map(Map.Entry::getValue).toList());
        }
    }

    private SnapshotPlan<Map<String, Object>, String> plan(
            SnapshotSource<Map<String, Object>> source) {
        return new SnapshotPlan<>(
                "rows",
                source,
                row -> "id",
                List.of(source),
                String.class,
                rows -> root -> List.of("value"));
    }
}
