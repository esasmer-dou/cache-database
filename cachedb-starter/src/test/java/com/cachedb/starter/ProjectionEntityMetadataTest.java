package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionCodec;
import com.reactor.cachedb.core.projection.ProjectionEntityMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectionEntityMetadataTest {

    @Test
    void shouldInheritProjectedColumnTypesFromBaseMetadata() {
        TestEntityMetadata baseMetadata = new TestEntityMetadata();
        EntityProjection<TestOrderEntity, TestOrderSummary, Long> projection = EntityProjection.of(
                "TestOrderSummaryHot",
                new ProjectionCodec<>() {
                    @Override
                    public String toRedisValue(TestOrderSummary projectionValue) {
                        return String.valueOf(projectionValue.orderId());
                    }

                    @Override
                    public TestOrderSummary fromRedisValue(String encoded) {
                        return new TestOrderSummary(Long.parseLong(encoded), 1L, Instant.EPOCH, Instant.EPOCH);
                    }
                },
                TestOrderSummary::orderId,
                List.of("order_id", "customer_id", "order_date", "created_at"),
                summary -> Map.of(
                        "order_id", summary.orderId(),
                        "customer_id", summary.customerId(),
                        "order_date", summary.orderDate(),
                        "created_at", summary.createdAt()
                ),
                entity -> new TestOrderSummary(entity.orderId(), entity.customerId(), entity.orderDate(), entity.createdAt())
        );

        ProjectionEntityMetadata<TestOrderSummary, Long> projectionMetadata = new ProjectionEntityMetadata<>(baseMetadata, projection);

        assertEquals(Map.of(
                "order_id", "java.lang.Long",
                "customer_id", "java.lang.Long",
                "order_date", "java.time.Instant",
                "created_at", "java.time.Instant"
        ), projectionMetadata.columnTypes());
    }

    private record TestOrderEntity(Long orderId, Long customerId, Instant orderDate, Instant createdAt, Double orderAmount) {
    }

    private record TestOrderSummary(Long orderId, Long customerId, Instant orderDate, Instant createdAt) {
    }

    private static final class TestEntityMetadata implements EntityMetadata<TestOrderEntity, Long> {

        @Override
        public String entityName() {
            return "TestOrderEntity";
        }

        @Override
        public String tableName() {
            return "test_order";
        }

        @Override
        public String redisNamespace() {
            return "test-order";
        }

        @Override
        public String idColumn() {
            return "order_id";
        }

        @Override
        public Class<TestOrderEntity> entityType() {
            return TestOrderEntity.class;
        }

        @Override
        public Function<TestOrderEntity, Long> idAccessor() {
            return TestOrderEntity::orderId;
        }

        @Override
        public List<String> columns() {
            return List.of("order_id", "customer_id", "order_date", "created_at", "order_amount");
        }

        @Override
        public Map<String, String> columnTypes() {
            LinkedHashMap<String, String> columnTypes = new LinkedHashMap<>();
            columnTypes.put("order_id", "java.lang.Long");
            columnTypes.put("customer_id", "java.lang.Long");
            columnTypes.put("order_date", "java.time.Instant");
            columnTypes.put("created_at", "java.time.Instant");
            columnTypes.put("order_amount", "java.lang.Double");
            return columnTypes;
        }

        @Override
        public List<RelationDefinition> relations() {
            return List.of();
        }
    }
}
