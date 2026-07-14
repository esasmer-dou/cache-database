package com.example.cachedb.sample.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DurableReferenceGuard {

    private static final String CUSTOMER_EXISTS_SQL =
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM sample_customers WHERE customer_id = ?) THEN 1 ELSE 0 END";
    private static final String ORDER_EXISTS_SQL =
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM sample_orders WHERE order_id = ?) THEN 1 ELSE 0 END";
    private static final String ORDER_HAS_LINES_SQL =
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM sample_order_lines WHERE order_id = ?) THEN 1 ELSE 0 END";
    private static final String SHIPMENT_EXISTS_SQL =
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM sample_shipments WHERE shipment_id = ?) THEN 1 ELSE 0 END";

    private final JdbcTemplate jdbcTemplate;

    public DurableReferenceGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requireCustomer(long customerId) {
        requireExists(CUSTOMER_EXISTS_SQL, customerId, "Customer", "customerId");
    }

    public void requireOrder(long orderId) {
        requireExists(ORDER_EXISTS_SQL, orderId, "Order", "orderId");
    }

    public void requireShipment(long shipmentId) {
        requireExists(SHIPMENT_EXISTS_SQL, shipmentId, "Shipment", "shipmentId");
    }

    public boolean orderHasDurableLines(long orderId) {
        return exists(ORDER_HAS_LINES_SQL, orderId);
    }

    private void requireExists(String sql, long id, String entityName, String idName) {
        if (!exists(sql, id)) {
            throw new DurableReferenceUnavailableException(
                    entityName + " " + idName + "=" + id
                            + " is not durable in SQL yet. Retry after write-behind flushes the parent row."
            );
        }
    }

    private boolean exists(String sql, long id) {
        Integer present = jdbcTemplate.queryForObject(sql, Integer.class, id);
        return present != null && present == 1;
    }
}
