package com.example.cachedb.sample.service;

import com.example.cachedb.sample.domain.OrderEntity;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.redis.RedisEntityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class SampleWarmBackfillService {

    private static final String CUSTOMER_ORDER_WINDOW_SQL = """
            SELECT order_id, customer_id, order_date, order_amount, currency_code, order_type, status, line_count, priority_score
            FROM sample_orders
            WHERE customer_id = ?
            ORDER BY order_date DESC, order_id DESC
            OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EntityRepository<OrderEntity, Long> orderRepository;

    public SampleWarmBackfillService(
            JdbcTemplate jdbcTemplate,
            EntityRepository<OrderEntity, Long> orderRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderRepository = orderRepository;
    }

    public WarmResult warmCustomerOrders(long customerId, int requestedLimit, boolean projectionOnly, boolean dryRun) {
        int limit = clamp(requestedLimit, 1, 1_000);
        List<OrderEntity> orders = jdbcTemplate.query(
                CUSTOMER_ORDER_WINDOW_SQL,
                (resultSet, rowNumber) -> {
                    OrderEntity order = new OrderEntity();
                    order.orderId = resultSet.getLong("order_id");
                    order.customerId = resultSet.getLong("customer_id");
                    order.orderDate = resultSet.getLong("order_date");
                    order.orderAmount = resultSet.getDouble("order_amount");
                    order.currencyCode = resultSet.getString("currency_code");
                    order.orderType = resultSet.getString("order_type");
                    order.status = resultSet.getString("status");
                    order.lineCount = resultSet.getInt("line_count");
                    order.priorityScore = resultSet.getDouble("priority_score");
                    return order;
                },
                customerId,
                limit
        );
        if (!dryRun && !orders.isEmpty()) {
            RedisEntityRepository<OrderEntity, Long> redisRepository = redisOrderRepository();
            if (projectionOnly) {
                redisRepository.hydrateProjectionWarmBatch(orders);
            } else {
                redisRepository.hydrateWarmBatch(orders, Collections.nCopies(orders.size(), 1L), true, false);
            }
        }
        return new WarmResult(customerId, limit, orders.size(), projectionOnly, dryRun);
    }

    @SuppressWarnings("unchecked")
    private RedisEntityRepository<OrderEntity, Long> redisOrderRepository() {
        if (orderRepository instanceof RedisEntityRepository<?, ?> redisRepository) {
            return (RedisEntityRepository<OrderEntity, Long>) redisRepository;
        }
        throw new IllegalStateException("Warm backfill requires RedisEntityRepository");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record WarmResult(
            long customerId,
            int requestedWindow,
            int rowsReadFromSql,
            boolean projectionOnly,
            boolean dryRun
    ) {
    }
}
