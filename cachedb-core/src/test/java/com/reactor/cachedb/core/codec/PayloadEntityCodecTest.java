package com.reactor.cachedb.core.codec;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.exception.CacheCodecException;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PayloadEntityCodecTest {
    record Row(Long id, String value) {}
    private EntityMetadata<Row, Long> metadata(int version) {
        return new EntityMetadata<>() {
            public String entityName() { return "Row"; }
            public String tableName() { return "rows"; }
            public String redisNamespace() { return "rows"; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, Long> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id", "value"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public int schemaVersion() { return version; }
            public CachePayloadUpcaster payloadUpcaster() {
                return new CachePayloadUpcaster() {
                    public int targetVersion() { return version; }
                    public String upcast(String raw, int from, int to) { return raw.replace("old", "new"); }
                };
            }
        };
    }
    private final EntityCodec<Row> raw = new EntityCodec<>() {
        public String toRedisValue(Row row) { return row.id() + ":" + row.value(); }
        public Row fromRedisValue(String value) { int i = value.indexOf(':'); return new Row(Long.valueOf(value.substring(0, i)), value.substring(i + 1)); }
        public Map<String, Object> toColumns(Row row) { return Map.of("id", row.id(), "value", row.value()); }
    };
    @Test void migratesLegacyPayloadAndPreservesCurrentAndCompressedFrames() {
        var codec = new PayloadEntityCodec<>(raw, metadata(2), 0);
        assertEquals(new Row(1L, "new"), codec.fromRedisValue("1:old"));
        assertEquals("~v2:1:new", codec.toRedisValue(new Row(1L, "new")));
        assertEquals(new Row(1L, "new"), codec.fromRedisValue("~v2:1:new"));
        String frame = new PayloadEntityCodec<>(raw, metadata(2), 32).toRedisValue(new Row(1L, "new".repeat(1000)));
        assertTrue(frame.startsWith("~Z:"));
        assertEquals(new Row(1L, "new".repeat(1000)), codec.fromRedisValue(frame));
        assertThrows(CacheCodecException.class, () -> codec.fromRedisValue("~v3:1:new"));
        assertThrows(CacheCodecException.class, () -> codec.fromRedisValue("~v-1:1:new"));
    }
    @Test void versionOneHasNoWriteFramingOverhead() {
        var codec = new PayloadEntityCodec<>(raw, metadata(1), 0);
        assertEquals("1:old", codec.toRedisValue(new Row(1L, "old")));
        assertEquals(new Row(1L, "old"), codec.fromRedisValue("~v1:1:old"));
    }
    @Test void inMemoryKitUsesTheSameSchemaAndCompressionBoundary() {
        var repository = new com.reactor.cachedb.core.testing.InMemoryEntityRepository<>(metadata(2), raw,
                com.reactor.cachedb.core.cache.CachePolicy.builder().compressionThresholdBytes(32).build());
        var row = new Row(1L, "new".repeat(1000));
        repository.save(row);
        assertEquals(row, repository.findById(1L).orElseThrow());
        assertEquals(List.of(row), repository.findAll(List.of(1L)));
    }
}
