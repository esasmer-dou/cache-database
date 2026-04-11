package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionCodec;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntityCacheBinding;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production-style read patterns for relation-heavy order screens.
 *
 * <p>The goal is to keep the first query cheap by returning only summary fields,
 * then load detail explicitly and in smaller slices when the caller really needs it.</p>
 */
public final class DemoOrderReadModelPatterns {

    private static final String FIELD_SEPARATOR = "\u001F";
    private static final String NULL_SENTINEL = "~";
    private static final double HIGH_LINE_TOTAL_SCALE = 100.0d;
    private static final double HIGH_LINE_RANK_BUCKET = 1_000_000_000_000d;
    public static final EntityProjection<DemoOrderEntity, OrderSummaryReadModel, Long> ORDER_SUMMARY_PROJECTION =
            EntityProjection.of(
                    "order-summary",
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(OrderSummaryReadModel projection) {
                            return String.join(
                                    FIELD_SEPARATOR,
                                    encodeString(projection.id() == null ? null : String.valueOf(projection.id())),
                                    encodeString(projection.customerId() == null ? null : String.valueOf(projection.customerId())),
                                    encodeString(projection.status()),
                                    String.valueOf(projection.lineItemCount()),
                                    String.valueOf(projection.totalAmount())
                            );
                        }

                        @Override
                        public OrderSummaryReadModel fromRedisValue(String encoded) {
                            String[] parts = encoded.split(FIELD_SEPARATOR, -1);
                            return new OrderSummaryReadModel(
                                    parseLong(parts[0]),
                                    parseLong(parts[1]),
                                    decodeString(parts[2]),
                                    Integer.parseInt(parts[3]),
                                    Double.parseDouble(parts[4])
                            );
                        }
                    },
                    OrderSummaryReadModel::id,
                    List.of("id", "customer_id", "status", "line_item_count", "total_amount"),
                    projection -> projectionColumns(
                            "id", projection.id(),
                            "customer_id", projection.customerId(),
                            "status", projection.status(),
                            "line_item_count", projection.lineItemCount(),
                            "total_amount", projection.totalAmount()
                    ),
                    (DemoOrderEntity order) -> new OrderSummaryReadModel(
                            order.id,
                            order.customerId,
                            order.status,
                            order.lineItemCount == null ? 0 : order.lineItemCount,
                            order.totalAmount == null ? 0.0 : order.totalAmount
                    )
            ).asyncRefresh();
    public static final EntityProjection<DemoOrderLineEntity, OrderLinePreviewReadModel, Long> ORDER_LINE_PREVIEW_PROJECTION =
            EntityProjection.of(
                    "order-line-preview",
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(OrderLinePreviewReadModel projection) {
                            return String.join(
                                    FIELD_SEPARATOR,
                                    encodeString(projection.id() == null ? null : String.valueOf(projection.id())),
                                    encodeString(projection.orderId() == null ? null : String.valueOf(projection.orderId())),
                                    encodeString(projection.lineNumber() == null ? null : String.valueOf(projection.lineNumber())),
                                    encodeString(projection.sku()),
                                    encodeString(projection.quantity() == null ? null : String.valueOf(projection.quantity())),
                                    encodeString(projection.lineTotal() == null ? null : String.valueOf(projection.lineTotal()))
                            );
                        }

                        @Override
                        public OrderLinePreviewReadModel fromRedisValue(String encoded) {
                            String[] parts = encoded.split(FIELD_SEPARATOR, -1);
                            return new OrderLinePreviewReadModel(
                                    parseLong(parts[0]),
                                    parseLong(parts[1]),
                                    parseInteger(parts[2]),
                                    decodeString(parts[3]),
                                    parseInteger(parts[4]),
                                    parseDouble(parts[5])
                            );
                        }
                    },
                    OrderLinePreviewReadModel::id,
                    List.of("id", "order_id", "line_number", "sku", "quantity", "line_total"),
                    projection -> projectionColumns(
                            "id", projection.id(),
                            "order_id", projection.orderId(),
                            "line_number", projection.lineNumber(),
                            "sku", projection.sku(),
                            "quantity", projection.quantity(),
                            "line_total", projection.lineTotal()
                    ),
                    (DemoOrderLineEntity line) -> new OrderLinePreviewReadModel(
                            line.id,
                            line.orderId,
                            line.lineNumber,
                            line.sku,
                            line.quantity,
                            line.lineTotal
                    )
            ).asyncRefresh();
    public static final EntityProjection<DemoOrderEntity, HighLineOrderSummaryReadModel, Long> HIGH_LINE_ORDER_SUMMARY_PROJECTION =
            EntityProjection.of(
                    "high-line-order-summary",
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(HighLineOrderSummaryReadModel projection) {
                            return String.join(
                                    FIELD_SEPARATOR,
                                    encodeString(projection.id() == null ? null : String.valueOf(projection.id())),
                                    encodeString(projection.status()),
                                    String.valueOf(projection.lineItemCount()),
                                    String.valueOf(projection.totalAmount()),
                                    String.valueOf(projection.rankScore())
                            );
                        }

                        @Override
                        public HighLineOrderSummaryReadModel fromRedisValue(String encoded) {
                            String[] parts = encoded.split(FIELD_SEPARATOR, -1);
                            return new HighLineOrderSummaryReadModel(
                                    parseLong(parts[0]),
                                    decodeString(parts[1]),
                                    Integer.parseInt(parts[2]),
                                    Double.parseDouble(parts[3]),
                                    Double.parseDouble(parts[4])
                            );
                        }
                    },
                    HighLineOrderSummaryReadModel::id,
                    List.of("id", "status", "line_item_count", "total_amount", "rank_score"),
                    projection -> projectionColumns(
                            "id", projection.id(),
                            "status", projection.status(),
                            "line_item_count", projection.lineItemCount(),
                            "total_amount", projection.totalAmount(),
                            "rank_score", projection.rankScore()
                    ),
                    (DemoOrderEntity order) -> new HighLineOrderSummaryReadModel(
                            order.id,
                            order.status,
                            order.lineItemCount == null ? 0 : order.lineItemCount,
                            order.totalAmount == null ? 0.0 : order.totalAmount,
                            highLineRankScore(
                                    order.lineItemCount == null ? 0 : order.lineItemCount,
                                    order.totalAmount == null ? 0.0 : order.totalAmount
                            )
                    )
            ).rankedBy("rank_score").asyncRefresh();
    public static final EntityProjection<DemoOrderLineEntity, HighLineLinePreviewReadModel, Long> HIGH_LINE_LINE_PREVIEW_PROJECTION =
            EntityProjection.of(
                    "high-line-line-preview",
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(HighLineLinePreviewReadModel projection) {
                            return String.join(
                                    FIELD_SEPARATOR,
                                    encodeString(projection.id() == null ? null : String.valueOf(projection.id())),
                                    encodeString(projection.orderId() == null ? null : String.valueOf(projection.orderId())),
                                    encodeString(projection.lineNumber() == null ? null : String.valueOf(projection.lineNumber())),
                                    encodeString(projection.quantity() == null ? null : String.valueOf(projection.quantity())),
                                    encodeString(projection.lineTotal() == null ? null : String.valueOf(projection.lineTotal()))
                            );
                        }

                        @Override
                        public HighLineLinePreviewReadModel fromRedisValue(String encoded) {
                            String[] parts = encoded.split(FIELD_SEPARATOR, -1);
                            return new HighLineLinePreviewReadModel(
                                    parseLong(parts[0]),
                                    parseLong(parts[1]),
                                    parseInteger(parts[2]),
                                    parseInteger(parts[3]),
                                    parseDouble(parts[4])
                            );
                        }
                    },
                    HighLineLinePreviewReadModel::id,
                    List.of("id", "order_id", "line_number", "quantity", "line_total"),
                    projection -> projectionColumns(
                            "id", projection.id(),
                            "order_id", projection.orderId(),
                            "line_number", projection.lineNumber(),
                            "quantity", projection.quantity(),
                            "line_total", projection.lineTotal()
                    ),
                    (DemoOrderLineEntity line) -> new HighLineLinePreviewReadModel(
                            line.id,
                            line.orderId,
                            line.lineNumber,
                            line.quantity,
                            line.lineTotal
                    )
            ).asyncRefresh();

    private final EntityRepository<DemoOrderEntity, Long> orderRepository;
    private final ProjectionRepository<OrderSummaryReadModel, Long> orderSummaryRepository;
    private final ProjectionRepository<OrderLinePreviewReadModel, Long> orderLinePreviewRepository;
    private final ProjectionRepository<HighLineOrderSummaryReadModel, Long> highLineOrderSummaryRepository;
    private final ProjectionRepository<HighLineLinePreviewReadModel, Long> highLineLinePreviewRepository;

    public DemoOrderReadModelPatterns(
            EntityRepository<DemoOrderEntity, Long> orderRepository,
            EntityRepository<DemoOrderLineEntity, Long> orderLineRepository
    ) {
        this.orderRepository = orderRepository;
        this.orderSummaryRepository = DemoOrderEntityCacheBinding.orderSummary(orderRepository);
        this.orderLinePreviewRepository = DemoOrderLineEntityCacheBinding.orderLinePreview(orderLineRepository);
        this.highLineOrderSummaryRepository = orderRepository.projected(HIGH_LINE_ORDER_SUMMARY_PROJECTION);
        this.highLineLinePreviewRepository = orderLineRepository.projected(HIGH_LINE_LINE_PREVIEW_PROJECTION);
    }

    public List<OrderSummaryReadModel> findCustomerOrderSummaries(long customerId, int limit) {
        return DemoOrderEntityCacheBinding.topCustomerOrders(orderSummaryRepository, customerId, limit);
    }

    public List<OrderSummaryReadModel> findHighLineOrderSummaries(int minimumLineCount, int limit) {
        return DemoOrderEntityCacheBinding.highLineOrders(orderSummaryRepository, minimumLineCount, limit);
    }

    public List<HighLineOrderSummaryReadModel> findHighLineCompactSummaries(int minimumLineCount, int limit) {
        return highLineOrderSummaryRepository.query(
                QuerySpec.where(QueryFilter.gte("line_item_count", minimumLineCount))
                        .orderBy(QuerySort.desc("rank_score"))
                        .limitTo(limit)
        );
    }

    public OrderDetailReadModel loadOrderDetail(long orderId, int previewLineLimit) {
        DemoOrderEntity order = DemoOrderEntityCacheBinding
                .orderLinesPreviewRepository(orderRepository, previewLineLimit)
                .findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        List<OrderLinePreviewReadModel> previewLines = order.orderLines == null
                ? List.of()
                : order.orderLines.stream()
                .map(line -> new OrderLinePreviewReadModel(
                        line.id,
                        order.id,
                        line.lineNumber,
                        line.sku,
                        line.quantity,
                        line.lineTotal
                ))
                .toList();
        return new OrderDetailReadModel(
                order.id,
                order.customerId,
                order.status,
                order.lineItemCount == null ? 0 : order.lineItemCount,
                order.totalAmount == null ? 0.0 : order.totalAmount,
                previewLines
        );
    }

    public List<OrderLinePreviewReadModel> loadPreviewLinesForOrder(long orderId, int previewLineLimit) {
        return loadRemainingOrderLines(orderId, 0, previewLineLimit);
    }

    public List<HighLineLinePreviewReadModel> loadHighLinePreviewLinesForOrder(long orderId, int previewLineLimit) {
        return highLineLinePreviewRepository.query(
                QuerySpec.where(QueryFilter.eq("order_id", orderId))
                        .orderBy(QuerySort.asc("line_number"))
                        .limitTo(Math.max(1, previewLineLimit))
        );
    }

    public List<OrderLinePreviewReadModel> loadRemainingOrderLines(long orderId, int offset, int pageSize) {
        return orderLinePreviewRepository.query(
                QuerySpec.where(QueryFilter.eq("order_id", orderId))
                        .orderBy(QuerySort.asc("line_number"))
                        .offsetBy(Math.max(0, offset))
                        .limitTo(Math.max(1, pageSize))
        );
    }

    public List<OrderLinePreviewReadModel> loadPreviewLinesForOrders(List<Long> orderIds, int totalLineLimit) {
        if (orderIds == null || orderIds.isEmpty() || totalLineLimit <= 0) {
            return List.of();
        }
        return DemoOrderLineEntityCacheBinding.previewLinesForOrders(
                orderLinePreviewRepository,
                orderIds,
                Math.max(1, totalLineLimit)
        );
    }

    public record OrderSummaryReadModel(
            Long id,
            Long customerId,
            String status,
            int lineItemCount,
            double totalAmount
    ) {
    }

    public record OrderDetailReadModel(
            Long id,
            Long customerId,
            String status,
            int lineItemCount,
            double totalAmount,
            List<OrderLinePreviewReadModel> previewLines
    ) {
    }

    public record OrderLinePreviewReadModel(
            Long id,
            Long orderId,
            Integer lineNumber,
            String sku,
            Integer quantity,
            Double lineTotal
    ) {
    }

    public record HighLineOrderSummaryReadModel(
            Long id,
            String status,
            int lineItemCount,
            double totalAmount,
            double rankScore
    ) {
    }

    public record HighLineLinePreviewReadModel(
            Long id,
            Long orderId,
            Integer lineNumber,
            Integer quantity,
            Double lineTotal
    ) {
    }

    private static String encodeString(String value) {
        if (value == null) {
            return NULL_SENTINEL;
        }
        return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeString(String encoded) {
        if (NULL_SENTINEL.equals(encoded)) {
            return null;
        }
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static Long parseLong(String encoded) {
        String decoded = decodeString(encoded);
        return decoded == null ? null : Long.parseLong(decoded);
    }

    private static Integer parseInteger(String encoded) {
        String decoded = decodeString(encoded);
        return decoded == null ? null : Integer.parseInt(decoded);
    }

    private static Double parseDouble(String encoded) {
        String decoded = decodeString(encoded);
        return decoded == null ? null : Double.parseDouble(decoded);
    }

    private static double highLineRankScore(int lineItemCount, double totalAmount) {
        long normalizedTotal = Math.max(0L, Math.round(totalAmount * HIGH_LINE_TOTAL_SCALE));
        long cappedTotal = Math.min(normalizedTotal, (long) HIGH_LINE_RANK_BUCKET - 1L);
        return (lineItemCount * HIGH_LINE_RANK_BUCKET) + cappedTotal;
    }

    private static Map<String, Object> projectionColumns(Object... values) {
        LinkedHashMap<String, Object> columns = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            columns.put(String.valueOf(values[index]), values[index + 1]);
        }
        return columns;
    }
}
