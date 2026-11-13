package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RedisCompositeRangeTest {
    record Row(String id, String tenant, String status, Integer score, boolean selected) {}
    static final Map<String, List<String>> PREFIX = Map.of("tenant_status", List.of("tenant", "status"));
    static EntityMetadata<Row, String> metadata(boolean tenantScoped, Map<String, String> sorts) {
        return new EntityMetadata<>() {
            public String entityName() { return "CompositeRow"; }
            public String tableName() { return "rows"; }
            public String redisNamespace() { return "rows"; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, String> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id", "tenant", "status", "score", "selected"); }
            public Map<String, String> columnTypes() { return Map.of("id", "java.lang.String", "tenant", "java.lang.String",
                    "status", "java.lang.String", "score", "java.lang.Integer", "selected", "boolean"); }
            public Map<String, List<String>> compositeExactIndexes() { return PREFIX; }
            public Map<String, String> compositeSortColumns() { return sorts; }
            public List<RelationDefinition> relations() { return List.of(); }
            public String tenantFieldName() { return tenantScoped ? "tenant" : null; }
            public String extractTenantId(Row row) { return row.tenant(); }
        };
    }
    static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) {
            return row.id() + "|" + row.tenant() + "|" + row.status() + "|"
                    + (row.score() == null ? "" : row.score()) + "|" + row.selected();
        }
        public Row fromRedisValue(String value) {
            String[] parts = value.split("\\|", -1);
            return new Row(parts[0], parts[1], parts[2], parts[3].isEmpty() ? null : Integer.valueOf(parts[3]),
                    Boolean.parseBoolean(parts[4]));
        }
        public Map<String, Object> toColumns(Row row) {
            var values = new LinkedHashMap<String, Object>();
            values.put("id", row.id()); values.put("tenant", row.tenant()); values.put("status", row.status());
            values.put("score", row.score()); values.put("selected", row.selected()); return values;
        }
    };
    static QuerySpec query(QueryFilter... extra) {
        QuerySpec query = QuerySpec.allOf(QueryFilter.eq("tenant", "a"), QueryFilter.eq("status", "OPEN"));
        for (QueryFilter filter : extra) query = query.and(filter);
        return query.orderBy(QuerySort.asc("score")).limitTo(10);
    }
    static RedisQueryIndexManager<Row, String> manager(RedisClientOperations client, RedisKeyStrategy keys,
            boolean tenant, Map<String, String> sorts) {
        return new RedisQueryIndexManager<>(client, metadata(tenant, sorts), CODEC, keys, null,
                QueryIndexConfig.defaults(), RelationConfig.defaults(), new QueryEvaluator(), null);
    }
    static String exact(RedisKeyStrategy keys) {
        return RedisCompositeIndexes.key(keys, "rows", "tenant_status", PREFIX.get("tenant_status"),
                Map.of("tenant", "a", "status", "OPEN"));
    }

    @Test void legacySignaturesAndEmptyDefaultRemainExact() {
        assertEquals("v1|tenant_status=tenant,status", RedisCompositeIndexes.signature(metadata(false, Map.of())));
        assertEquals("v1|tenant_status=tenant,status>score",
                RedisCompositeIndexes.signature(metadata(false, Map.of("tenant_status", "score"))));
        assertTrue(new EntityMetadata<Object, Object>() {
            public String entityName() { return "x"; } public String tableName() { return "x"; }
            public String redisNamespace() { return "x"; } public String idColumn() { return "id"; }
            public Class<Object> entityType() { return Object.class; } public Function<Object, Object> idAccessor() { return Function.identity(); }
            public List<String> columns() { return List.of("id"); } public List<RelationDefinition> relations() { return List.of(); }
        }.compositeSortColumns().isEmpty());
    }

    @Test void markerUsesSameScoreAndTupleEncodingAndDiffTracksScoreAndTupleChanges() {
        var keys = new ClusterHashTagKeyStrategy("composite");
        var metadata = metadata(false, Map.of("tenant_status", "score"));
        var before = new LinkedHashMap<String, String>();
        RedisCompositeIndexes.append(keys, metadata, CODEC.toColumns(new Row("1", "a", "OPEN", 10, true)), before);
        assertEquals(exact(keys) + ":sort|10.0", before.get(RedisCompositeIndexes.SORT_MARKER + "tenant_status"));
        assertEquals(RedisScoreSupport.toScore(metadata.columnTypes(), "score", 10),
                RedisCompositeIndexes.sortEntry(before.get(RedisCompositeIndexes.SORT_MARKER + "tenant_status")).score());
        var next = new LinkedHashMap<String, String>();
        RedisCompositeIndexes.append(keys, metadata, CODEC.toColumns(new Row("1", "b", "OPEN", 20, true)), next);
        assertTrue(RedisIndexMutationPlanner.plan(before, next, false).changed().contains(RedisCompositeIndexes.SORT_MARKER + "tenant_status"));
        assertNotEquals(before, next);
        assertTrue(RedisIndexMutationPlanner.plan(before, before, false).unchanged());
        assertThrows(IllegalArgumentException.class, () -> RedisCompositeIndexes.sortEntry("bad|NaN"));
        assertThrows(IllegalArgumentException.class, () -> RedisCompositeIndexes.sortEntry("key:sort|Infinity"));
        var nullScore = new LinkedHashMap<String, String>();
        RedisCompositeIndexes.append(keys, metadata, CODEC.toColumns(new Row("1", "a", "OPEN", null, true)), nullScore);
        assertEquals(Set.of(RedisCompositeIndexes.MARKER + "tenant_status"), nullScore.keySet());
    }

    @Test void sortMetadataAndUtf8ByteGuardsAreEnforcedBeforeMutation() {
        assertThrows(IllegalArgumentException.class, () -> RedisCompositeIndexes.validateMember("\u0800".repeat(1366)));
        assertDoesNotThrow(() -> RedisCompositeIndexes.validateMember("x".repeat(4096)));
        assertThrows(IllegalArgumentException.class, () -> RedisCompositeIndexes.validateSortMeta(
                Map.of(RedisCompositeIndexes.SORT_MARKER + "bad-name", "a:sort|1")));
        assertThrows(IllegalArgumentException.class, () -> RedisCompositeIndexes.validateSortMeta(
                Map.of(RedisCompositeIndexes.SORT_MARKER + "valid", "a:sort|NaN")));
        var tooMany = new LinkedHashMap<String, String>();
        for (int i = 0; i < 33; i++) tooMany.put(RedisCompositeIndexes.SORT_MARKER + "i" + i, "a:sort|1");
        assertThrows(IllegalArgumentException.class, () -> RedisCompositeIndexes.validateSortMeta(tooMany));
    }

    @Test void handwrittenMetadataIsValidatedAndRedisLexicalOrderIsNotNumericOrUtf16() {
        for (var declaration : List.of(Map.of("missing", "score"), Map.of("tenant_status", "tenant"),
                Map.of("tenant_status", "selected"))) {
            assertThrows(IllegalArgumentException.class, () -> RedisCompositeIndexes.validate(metadata(false, declaration)));
        }
        assertTrue(RedisCompositeIndexes.compareMembers("10", "2") < 0);
        assertTrue(RedisCompositeIndexes.compareMembers("\uE000", "\uD800\uDC00") < 0);
        assertThrows(IllegalArgumentException.class, () -> new RedisQueryIndexManager.CompositePosition(Double.NaN, "1"));
        assertThrows(IllegalArgumentException.class, () -> new RedisQueryIndexManager.CompositePosition(1, "\uD800"));
        assertEquals(0d, new RedisQueryIndexManager.CompositePosition(-0d, "1").score());
        assertEquals(4, RedisQueryIndexManager.COMPOSITE_MAX_ROUNDS);
        assertEquals(10_000, RedisQueryIndexManager.COMPOSITE_MAX_EXAMINED_IDS);
    }
}
