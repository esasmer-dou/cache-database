package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.core.registry.*;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcReorderingMetricsTest {
    record Row(String id) {}
    static class Flusher extends AbstractJdbcWriteBehindFlusher {
        Flusher(EntityRegistry registry) { super(new JdbcDataSource(),registry,JdbcWriteFence.Provider.POSTGRES,5); }
        public void flush(QueuedWriteOperation operation) { throw new AssertionError("Planning must not run SQL"); }
        public WriteFailureDetails classify(Exception failure) { return WriteFailureDetails.unknown(failure); }
    }
    private static EntityMetadata<Row,String> metadata(String name, boolean independent) {
        return new EntityMetadata<>() {
            public String entityName() { return name; }
            public String tableName() { return name.toLowerCase(Locale.ROOT); }
            public String redisNamespace() { return name; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row,String> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id","value"); }
            public Map<String,String> columnTypes() { return Map.of("id","java.lang.String","value","java.lang.String"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public boolean independentWriteReordering() { return independent; }
        };
    }
    private static QueuedWriteOperation operation(String entity, String id, OperationType type, long version) {
        return new QueuedWriteOperation(type,entity,entity.toLowerCase(Locale.ROOT),entity,"","id","entity_version",null,id,
                Map.of("value","v"),version,Instant.EPOCH);
    }
    @Test void optInMetricsDistinguishRegroupedMixedAndSameKeyChains() {
        var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        EntityCodec<Row> codec = new EntityCodec<>() {
            public String toRedisValue(Row row) { return row.id(); }
            public Row fromRedisValue(String value) { return new Row(value); }
            public Map<String,Object> toColumns(Row row) { return Map.of("id",row.id()); }
        };
        for (String name: List.of("Safe","Ordered")) registry.register(metadata(name,name.equals("Safe")),codec,CachePolicy.defaults(),
                new NoOpRelationBatchLoader<>(),new NoOpEntityPageLoader<>());
        var flusher = new Flusher(registry);
        var a = operation("Safe","1",OperationType.UPSERT,1);
        var b = operation("Safe","2",OperationType.PATCH,1);
        var c = operation("Safe","3",OperationType.UPSERT,1);
        assertEquals(List.of(a,c,b),flusher.compactIndependentWrites(List.of(a,b,c)));
        assertEquals(1,flusher.reorderedBatches()); assertEquals(3,flusher.reorderedOperations());
        var mixed = List.of(a,operation("Ordered","4",OperationType.UPSERT,1),c);
        assertSame(mixed,flusher.compactIndependentWrites(mixed));
        assertEquals(1,flusher.reorderSkippedMixedOptIn()); assertEquals(1,flusher.reorderedBatches());
        var chain = List.of(a,operation("Safe","1",OperationType.PATCH,2),operation("Safe","1",OperationType.DELETE,3));
        assertEquals(chain,flusher.compactIndependentWrites(chain));
        assertEquals(2,flusher.reorderedBatches()); assertEquals(6,flusher.reorderedOperations());
    }
}
