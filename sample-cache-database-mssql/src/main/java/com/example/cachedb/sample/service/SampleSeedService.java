package com.example.cachedb.sample.service;

import com.example.cachedb.sample.domain.AuditEventEntity;
import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderLineEntity;
import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.reactor.cachedb.core.api.EntityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Service
public class SampleSeedService {

    private final EntityRepository<CustomerEntity, Long> customerRepository;
    private final EntityRepository<ProductEntity, Long> productRepository;
    private final EntityRepository<OrderEntity, Long> orderRepository;
    private final EntityRepository<OrderLineEntity, Long> lineRepository;
    private final EntityRepository<SupportTicketEntity, Long> ticketRepository;
    private final EntityRepository<ShipmentEntity, Long> shipmentRepository;
    private final EntityRepository<ShipmentEventEntity, Long> shipmentEventRepository;
    private final EntityRepository<ReportJobEntity, Long> reportJobRepository;
    private final EntityRepository<AuditEventEntity, Long> auditEventRepository;
    private final JdbcTemplate jdbcTemplate;

    public SampleSeedService(
            EntityRepository<CustomerEntity, Long> customerRepository,
            EntityRepository<ProductEntity, Long> productRepository,
            EntityRepository<OrderEntity, Long> orderRepository,
            EntityRepository<OrderLineEntity, Long> lineRepository,
            EntityRepository<SupportTicketEntity, Long> ticketRepository,
            EntityRepository<ShipmentEntity, Long> shipmentRepository,
            EntityRepository<ShipmentEventEntity, Long> shipmentEventRepository,
            EntityRepository<ReportJobEntity, Long> reportJobRepository,
            EntityRepository<AuditEventEntity, Long> auditEventRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.ticketRepository = ticketRepository;
        this.shipmentRepository = shipmentRepository;
        this.shipmentEventRepository = shipmentEventRepository;
        this.reportJobRepository = reportJobRepository;
        this.auditEventRepository = auditEventRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public SeedResult seed(int customerCount, int ordersPerCustomer, int linesPerOrder) {
        int customers = clamp(customerCount, 1, 200);
        int ordersEach = clamp(ordersPerCustomer, 1, 200);
        int linesEach = clamp(linesPerOrder, 1, 12);
        long now = Instant.now().getEpochSecond();

        for (long productId = 1; productId <= 50; productId++) {
            productRepository.save(product(productId));
        }
        for (long customerId = 1; customerId <= customers; customerId++) {
            customerRepository.save(customer(customerId, now));
        }

        waitForRows("sample_products", 50);
        waitForRows("sample_customers", customers);

        long orderCount = 0;
        long lineCount = 0;
        long shipmentCount = 0;
        long shipmentEventCount = 0;
        long reportJobCount = 0;
        long auditEventCount = 0;
        for (long customerId = 1; customerId <= customers; customerId++) {
            for (int index = 1; index <= ordersEach; index++) {
                long orderId = (customerId * 10_000L) + index;
                OrderEntity order = order(orderId, customerId, now - (index * 3_600L), linesEach);
                orderRepository.save(order);
                orderCount++;
            }
            for (int shipmentIndex = 1; shipmentIndex <= 3; shipmentIndex++) {
                long shipmentId = (customerId * 20_000L) + shipmentIndex;
                shipmentRepository.save(shipment(shipmentId, customerId, shipmentIndex, now));
                shipmentCount++;
            }
        }

        for (long reportJobId = 1; reportJobId <= 20; reportJobId++) {
            reportJobRepository.save(reportJob(reportJobId, now));
            reportJobCount++;
        }

        waitForRows("sample_orders", orderCount);
        waitForRows("sample_shipments", shipmentCount);
        waitForRows("sample_report_jobs", reportJobCount);

        for (long customerId = 1; customerId <= customers; customerId++) {
            for (int orderIndex = 1; orderIndex <= ordersEach; orderIndex++) {
                long orderId = (customerId * 10_000L) + orderIndex;
                for (int lineNumber = 1; lineNumber <= linesEach; lineNumber++) {
                    lineRepository.save(line(orderId, lineNumber));
                    lineCount++;
                }
            }
            for (int shipmentIndex = 1; shipmentIndex <= 3; shipmentIndex++) {
                long shipmentId = (customerId * 20_000L) + shipmentIndex;
                for (int eventIndex = 1; eventIndex <= 4; eventIndex++) {
                    shipmentEventRepository.save(shipmentEvent(shipmentId, eventIndex, now));
                    shipmentEventCount++;
                }
            }
            ticketRepository.save(ticket(customerId, now));
            auditEventRepository.save(auditEvent(auditEventCount + 1, "CustomerEntity", customerId, now));
            auditEventCount++;
        }

        waitForRows("sample_order_lines", lineCount);
        waitForRows("sample_shipment_events", shipmentEventCount);
        waitForRows("sample_support_tickets", customers);
        waitForRows("sample_audit_events", auditEventCount);

        return new SeedResult(
                customers,
                50,
                orderCount,
                lineCount,
                customers,
                shipmentCount,
                shipmentEventCount,
                reportJobCount,
                auditEventCount
        );
    }

    private ProductEntity product(long productId) {
        long now = Instant.now().getEpochSecond();
        ProductEntity product = new ProductEntity();
        product.productId = productId;
        product.sku = "SKU-" + productId;
        product.productName = "Sample Product " + productId;
        product.category = productId % 3 == 0 ? "electronics" : productId % 3 == 1 ? "grocery" : "home";
        product.activeStatus = productId % 17 == 0 ? "INACTIVE" : "ACTIVE";
        product.unitPrice = 10.0 + productId;
        product.stockQuantity = productId % 10 == 0 ? 8 : 500 - (int) productId;
        product.reservedQuantity = productId % 10 == 0 ? 5 : (int) (productId % 7);
        product.stockStatus = productId % 10 == 0 ? "LOW_STOCK" : productId % 8 == 0 ? "RESERVED" : "IN_STOCK";
        product.updatedAt = now - (productId * 3_600L);
        return product;
    }

    private CustomerEntity customer(long customerId, long now) {
        CustomerEntity customer = new CustomerEntity();
        customer.customerId = customerId;
        customer.taxNumber = "TAX-" + (100_000 + customerId);
        customer.customerType = customerId % 5 == 0 ? "CORPORATE" : "RETAIL";
        customer.segment = customerId % 7 == 0 ? "VIP" : customerId % 3 == 0 ? "LOYAL" : "STANDARD";
        customer.status = "ACTIVE";
        customer.createdAt = now - 15_552_000L;
        customer.updatedAt = now;
        return customer;
    }

    private OrderEntity order(long orderId, long customerId, long orderDate, int linesEach) {
        OrderEntity order = new OrderEntity();
        order.orderId = orderId;
        order.customerId = customerId;
        order.orderDate = orderId % 37 == 0 ? orderDate - (120L * 86_400L) : orderDate;
        order.orderAmount = 100.0 + (orderId % 700);
        order.currencyCode = "USD";
        order.orderType = orderId % 4 == 0 ? "EXPRESS" : "STANDARD";
        order.status = orderId % 37 == 0 ? "COMPLETED" : orderId % 6 == 0 ? "PAID" : "NEW";
        order.lineCount = linesEach;
        order.priorityScore = (order.orderAmount / 10.0) + (order.orderType.equals("EXPRESS") ? 25.0 : 0.0);
        return order;
    }

    private OrderLineEntity line(long orderId, int lineNumber) {
        long productId = ((orderId + lineNumber) % 50) + 1;
        OrderLineEntity line = new OrderLineEntity();
        line.lineId = (orderId * 100L) + lineNumber;
        line.orderId = orderId;
        line.productId = productId;
        line.lineNumber = lineNumber;
        line.sku = "SKU-" + productId;
        line.quantity = (lineNumber % 4) + 1;
        line.unitPrice = 10.0 + productId;
        line.lineTotal = line.quantity * line.unitPrice;
        line.status = "ACTIVE";
        return line;
    }

    private SupportTicketEntity ticket(long customerId, long now) {
        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.ticketId = customerId;
        ticket.customerId = customerId;
        ticket.priority = customerId % 7 == 0 ? "HIGH" : "NORMAL";
        ticket.status = customerId % 4 == 0 ? "OPEN" : "PENDING";
        ticket.subject = "Customer onboarding check " + customerId;
        ticket.openedAt = now - 86_400L;
        ticket.updatedAt = now;
        return ticket;
    }

    private ShipmentEntity shipment(long shipmentId, long customerId, int shipmentIndex, long now) {
        ShipmentEntity shipment = new ShipmentEntity();
        shipment.shipmentId = shipmentId;
        shipment.customerId = customerId;
        shipment.trackingNumber = "TRK-" + shipmentId;
        shipment.carrierCode = shipmentIndex % 2 == 0 ? "UPS" : "DHL";
        shipment.shipmentStatus = switch ((int) ((customerId + shipmentIndex) % 5)) {
            case 0 -> "DELAYED";
            case 1 -> "IN_TRANSIT";
            case 2 -> "OUT_FOR_DELIVERY";
            case 3 -> "DELIVERED";
            default -> "EXCEPTION";
        };
        shipment.currentCity = shipmentIndex % 2 == 0 ? "Istanbul" : "Ankara";
        shipment.promisedAt = now + (shipmentIndex * 86_400L);
        shipment.updatedAt = "DELIVERED".equals(shipment.shipmentStatus)
                ? now - (45L * 86_400L)
                : now - (shipmentIndex * 7_200L);
        shipment.riskScore = "EXCEPTION".equals(shipment.shipmentStatus)
                ? 95.0
                : "DELAYED".equals(shipment.shipmentStatus) ? 80.0 : 25.0 + shipmentIndex;
        return shipment;
    }

    private ShipmentEventEntity shipmentEvent(long shipmentId, int eventIndex, long now) {
        ShipmentEventEntity event = new ShipmentEventEntity();
        event.eventId = (shipmentId * 100L) + eventIndex;
        event.shipmentId = shipmentId;
        event.eventType = switch (eventIndex) {
            case 1 -> "PICKED_UP";
            case 2 -> "IN_TRANSIT";
            case 3 -> shipmentId % 5 == 0 ? "DELAY" : "HUB_SCAN";
            default -> shipmentId % 7 == 0 ? "EXCEPTION" : "OUT_FOR_DELIVERY";
        };
        event.eventCity = eventIndex % 2 == 0 ? "Istanbul" : "Ankara";
        event.eventTime = now - (eventIndex * 3_600L);
        event.severity = "EXCEPTION".equals(event.eventType) ? "ERROR" : "DELAY".equals(event.eventType) ? "WARN" : "INFO";
        event.description = "Shipment " + shipmentId + " event " + event.eventType;
        return event;
    }

    private ReportJobEntity reportJob(long reportJobId, long now) {
        ReportJobEntity reportJob = new ReportJobEntity();
        reportJob.reportJobId = reportJobId;
        reportJob.reportType = reportJobId % 3 == 0 ? "LEDGER_EXPORT" : reportJobId % 3 == 1 ? "ORDER_SUMMARY" : "SLA_AUDIT";
        reportJob.status = switch ((int) (reportJobId % 5)) {
            case 0 -> "FAILED";
            case 1 -> "QUEUED";
            case 2 -> "RUNNING";
            default -> "COMPLETED";
        };
        reportJob.requestedBy = reportJobId % 2 == 0 ? "ops@example.com" : "finance@example.com";
        reportJob.createdAt = now - (reportJobId * 1_800L);
        reportJob.updatedAt = now - (reportJobId * 900L);
        reportJob.rowCount = "COMPLETED".equals(reportJob.status) ? (int) reportJobId * 1_000 : 0;
        reportJob.failureReason = "FAILED".equals(reportJob.status) ? "Sample downstream timeout" : null;
        return reportJob;
    }

    private AuditEventEntity auditEvent(long auditEventId, String entityName, long entityId, long now) {
        AuditEventEntity event = new AuditEventEntity();
        event.auditEventId = auditEventId;
        event.entityName = entityName;
        event.entityId = entityId;
        event.eventType = auditEventId % 5 == 0 ? "SECURITY_REVIEW" : "ENTITY_UPSERT";
        event.severity = auditEventId % 5 == 0 ? "SECURITY" : auditEventId % 3 == 0 ? "WARN" : "INFO";
        event.actor = auditEventId % 2 == 0 ? "system" : "sample-user";
        event.createdAt = now - (auditEventId * 300L);
        event.message = "Sample audit event for " + entityName + " " + entityId;
        return event;
    }

    private void waitForRows(String table, long expectedRows) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            if (rows != null && rows >= expectedRows) {
                return;
            }
            sleepQuietly();
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record SeedResult(
            int customers,
            int products,
            long orders,
            long orderLines,
            int supportTickets,
            long shipments,
            long shipmentEvents,
            long reportJobs,
            long auditEvents
    ) {
    }
}
