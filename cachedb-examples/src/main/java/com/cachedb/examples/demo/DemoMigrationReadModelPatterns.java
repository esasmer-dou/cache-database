package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionCodec;
import com.reactor.cachedb.examples.demo.entity.MigrationDemoOrderEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DemoMigrationReadModelPatterns {

    private static final String FIELD_SEPARATOR = "\u001F";
    private static final String NULL_SENTINEL = "~";

    public static final String SUMMARY_PROJECTION_NAME = "MigrationDemoCustomerEntityMigrationDemoOrderEntitySummaryHot";
    public static final String RANKED_PROJECTION_NAME = "MigrationDemoCustomerEntityMigrationDemoOrderEntityRankedHot";

    public static final EntityProjection<MigrationDemoOrderEntity, MigrationOrderSummaryView, Long> SUMMARY_PROJECTION =
            EntityProjection.<MigrationDemoOrderEntity, MigrationOrderSummaryView, Long>of(
                    SUMMARY_PROJECTION_NAME,
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(MigrationOrderSummaryView projection) {
                            return String.join(
                                    FIELD_SEPARATOR,
                                    encode(projection.orderId()),
                                    encode(projection.customerId()),
                                    encodeInstant(projection.orderDate()),
                                    encodeInstant(projection.createdAt()),
                                    encodeDouble(projection.orderAmount()),
                                    encodeString(projection.currencyCode()),
                                    encodeString(projection.orderType())
                            );
                        }

                        @Override
                        public MigrationOrderSummaryView fromRedisValue(String encoded) {
                            String[] parts = encoded.split(FIELD_SEPARATOR, -1);
                            return new MigrationOrderSummaryView(
                                    parseLong(parts[0]),
                                    parseLong(parts[1]),
                                    parseInstant(parts[2]),
                                    parseInstant(parts[3]),
                                    parseDouble(parts[4]),
                                    decodeString(parts[5]),
                                    decodeString(parts[6])
                            );
                        }
                    },
                    MigrationOrderSummaryView::orderId,
                    List.of("order_id", "customer_id", "order_date", "created_at", "order_amount", "currency_code", "order_type"),
                    projection -> projectionColumns(
                            "order_id", projection.orderId(),
                            "customer_id", projection.customerId(),
                            "order_date", projection.orderDate(),
                            "created_at", projection.createdAt(),
                            "order_amount", projection.orderAmount(),
                            "currency_code", projection.currencyCode(),
                            "order_type", projection.orderType()
                    ),
                    order -> new MigrationOrderSummaryView(
                            order.orderId,
                            order.customerId,
                            order.orderDate,
                            order.createdAt,
                            order.orderAmount,
                            order.currencyCode,
                            order.orderType
                    )
            ).asyncRefresh();

    public static final EntityProjection<MigrationDemoOrderEntity, MigrationOrderRankedView, Long> RANKED_PROJECTION =
            EntityProjection.<MigrationDemoOrderEntity, MigrationOrderRankedView, Long>of(
                    RANKED_PROJECTION_NAME,
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(MigrationOrderRankedView projection) {
                            return String.join(
                                    FIELD_SEPARATOR,
                                    encode(projection.orderId()),
                                    encode(projection.customerId()),
                                    encodeInstant(projection.orderDate()),
                                    encodeInstant(projection.createdAt()),
                                    encodeDouble(projection.orderAmount()),
                                    encodeString(projection.currencyCode()),
                                    encodeString(projection.orderType()),
                                    encodeDouble(projection.rankScore())
                            );
                        }

                        @Override
                        public MigrationOrderRankedView fromRedisValue(String encoded) {
                            String[] parts = encoded.split(FIELD_SEPARATOR, -1);
                            return new MigrationOrderRankedView(
                                    parseLong(parts[0]),
                                    parseLong(parts[1]),
                                    parseInstant(parts[2]),
                                    parseInstant(parts[3]),
                                    parseDouble(parts[4]),
                                    decodeString(parts[5]),
                                    decodeString(parts[6]),
                                    parseDouble(parts[7])
                            );
                        }
                    },
                    MigrationOrderRankedView::orderId,
                    List.of("order_id", "customer_id", "order_date", "created_at", "order_amount", "currency_code", "order_type", "rank_score"),
                    projection -> projectionColumns(
                            "order_id", projection.orderId(),
                            "customer_id", projection.customerId(),
                            "order_date", projection.orderDate(),
                            "created_at", projection.createdAt(),
                            "order_amount", projection.orderAmount(),
                            "currency_code", projection.currencyCode(),
                            "order_type", projection.orderType(),
                            "rank_score", projection.rankScore()
                    ),
                    order -> new MigrationOrderRankedView(
                            order.orderId,
                            order.customerId,
                            order.orderDate,
                            order.createdAt,
                            order.orderAmount,
                            order.currencyCode,
                            order.orderType,
                            order.rankScore == null ? 0.0d : order.rankScore
                    )
            ).rankedBy("rank_score").asyncRefresh();

    private DemoMigrationReadModelPatterns() {
    }

    public record MigrationOrderSummaryView(
            Long orderId,
            Long customerId,
            Instant orderDate,
            Instant createdAt,
            Double orderAmount,
            String currencyCode,
            String orderType
    ) {
    }

    public record MigrationOrderRankedView(
            Long orderId,
            Long customerId,
            Instant orderDate,
            Instant createdAt,
            Double orderAmount,
            String currencyCode,
            String orderType,
            Double rankScore
    ) {
    }

    private static Map<String, Object> projectionColumns(Object... values) {
        LinkedHashMap<String, Object> columns = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            columns.put(String.valueOf(values[index]), values[index + 1]);
        }
        return columns;
    }

    private static String encode(Object value) {
        return value == null ? NULL_SENTINEL : String.valueOf(value);
    }

    private static String encodeDouble(Double value) {
        return value == null ? NULL_SENTINEL : String.valueOf(value);
    }

    private static String encodeInstant(Instant value) {
        return value == null ? NULL_SENTINEL : String.valueOf(value.toEpochMilli());
    }

    private static String encodeString(String value) {
        return value == null ? NULL_SENTINEL : value;
    }

    private static String decodeString(String encoded) {
        return NULL_SENTINEL.equals(encoded) ? null : encoded;
    }

    private static Long parseLong(String encoded) {
        String decoded = decodeString(encoded);
        return decoded == null ? null : Long.parseLong(decoded);
    }

    private static Double parseDouble(String encoded) {
        String decoded = decodeString(encoded);
        return decoded == null ? null : Double.parseDouble(decoded);
    }

    private static Instant parseInstant(String encoded) {
        String decoded = decodeString(encoded);
        return decoded == null ? null : Instant.ofEpochMilli(Long.parseLong(decoded));
    }
}
