package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import java.util.*;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcTenantOwnershipTest {
    record Row(Long id, String tenant) {}
    @Test void ownershipLookupIsCompleteBoundedAndIncludesSoftDeletedForeignRows() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owned_rows(id BIGINT PRIMARY KEY, tenant VARCHAR(40), deleted VARCHAR(5), entity_version BIGINT)");
            statement.execute("INSERT INTO owned_rows VALUES(1,'a','false',1),(2,'b','true',1)");
        }
        EntityMetadata<Row, Long> metadata = new EntityMetadata<>() {
            public String entityName() { return "OwnedRow"; }
            public String tableName() { return "owned_rows"; }
            public String redisNamespace() { return "owned_rows"; }
            public String idColumn() { return "id"; }
            public String deletedColumn() { return "deleted"; }
            public String tenantFieldName() { return "tenant"; }
            public String extractTenantId(Row row) { return row.tenant(); }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, Long> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id", "tenant"); }
            public Map<String, String> columnTypes() { return Map.of("id", "java.lang.Long", "tenant", "java.lang.String", "deleted", "java.lang.Boolean"); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
        EntityCodec<Row> codec = new EntityCodec<>() {
            public String toRedisValue(Row row) { throw new UnsupportedOperationException(); }
            public Row fromRedisValue(String payload) { throw new UnsupportedOperationException(); }
            public Map<String, Object> toColumns(Row row) { return Map.of("id", row.id(), "tenant", row.tenant()); }
            public Row fromColumns(Map<String, Object> values) { return new Row(((Number) values.get("id")).longValue(), (String) values.get("tenant")); }
        };
        var loader = new JdbcEntitySourceLoader<>(source, metadata, codec, 1, 5);
        assertThrows(CacheTenantAccessDeniedException.class, () -> loader.loadTenantOwners(List.of(1L, 2L)));
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertTrue(loader.load(2L).isEmpty());
        }
        try (var admin = CacheTenantContext.administrative()) {
            assertEquals(Map.of(1L, "a", 2L, "b"), loader.loadTenantOwners(List.of(1L, 2L, 3L)));
            assertThrows(IllegalArgumentException.class, () -> loader.loadTenantOwners(java.util.Collections.nCopies(501, 1L)));
        }
    }
}
