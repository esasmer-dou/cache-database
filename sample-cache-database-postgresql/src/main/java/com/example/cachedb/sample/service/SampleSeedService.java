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
import com.example.cachedb.sample.repository.SampleRepositories;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

@Service
public class SampleSeedService {

    private static final int WRITE_BATCH_SIZE = 128;
    private static final int MAX_PENDING_RECEIPTS = 1_024;
    private static final Duration DURABILITY_TIMEOUT = Duration.ofSeconds(30);

    private final SampleRepositories repositories;
    private final CacheDatabase cacheDatabase;

    public SampleSeedService(
            SampleRepositories repositories,
            CacheDatabase cacheDatabase
    ) {
        this.repositories = repositories;
        this.cacheDatabase = cacheDatabase;
    }

    public SeedResult seed(int customerCount, int ordersPerCustomer, int linesPerOrder) {
        int customers = requireInRange("customerCount", customerCount, 1, 200);
        int ordersEach = requireInRange("ordersPerCustomer", ordersPerCustomer, 1, 200);
        int linesEach = requireInRange("linesPerOrder", linesPerOrder, 1, 12);
        long now = Instant.now().getEpochSecond();

        DurableBatch<ProductEntity, Long> products = batch("products", repositories.products()::saveAll);
        DurableBatch<CustomerEntity, Long> customerBatch = batch("customers", repositories.customers()::saveAll);
        for (long productId = 1; productId <= 50; productId++) {
            products.add(product(productId));
        }
        for (long customerId = 1; customerId <= customers; customerId++) {
            customerBatch.add(customer(customerId, now));
        }
        products.finish();
        customerBatch.finish();

        long orderCount = 0;
        long lineCount = 0;
        long shipmentCount = 0;
        long shipmentEventCount = 0;
        long reportJobCount = 0;
        long auditEventCount = 0;
        DurableBatch<OrderEntity, Long> orders = batch("orders", repositories.orders()::saveAll);
        DurableBatch<ShipmentEntity, Long> shipments = batch("shipments", repositories.shipments()::saveAll);
        for (long customerId = 1; customerId <= customers; customerId++) {
            for (int index = 1; index <= ordersEach; index++) {
                long orderId = (customerId * 10_000L) + index;
                OrderEntity order = order(orderId, customerId, now - (index * 3_600L), linesEach);
                orders.add(order);
                orderCount++;
            }
            for (int shipmentIndex = 1; shipmentIndex <= 3; shipmentIndex++) {
                long shipmentId = (customerId * 20_000L) + shipmentIndex;
                shipments.add(shipment(shipmentId, customerId, shipmentIndex, now));
                shipmentCount++;
            }
        }

        DurableBatch<ReportJobEntity, Long> reportJobs = batch("report jobs", repositories.reportJobs()::saveAll);
        for (long reportJobId = 1; reportJobId <= 20; reportJobId++) {
            reportJobs.add(reportJob(reportJobId, now));
            reportJobCount++;
        }
        orders.finish();
        shipments.finish();
        reportJobs.finish();

        DurableBatch<OrderLineEntity, Long> lines = batch("order lines", repositories.orderLines()::saveAll);
        DurableBatch<ShipmentEventEntity, Long> shipmentEvents = batch(
                "shipment events", repositories.shipmentEvents()::saveAll
        );
        DurableBatch<SupportTicketEntity, Long> tickets = batch(
                "support tickets", repositories.supportTickets()::saveAll
        );
        DurableBatch<AuditEventEntity, Long> auditEvents = batch("audit events", repositories.auditEvents()::saveAll);
        for (long customerId = 1; customerId <= customers; customerId++) {
            for (int orderIndex = 1; orderIndex <= ordersEach; orderIndex++) {
                long orderId = (customerId * 10_000L) + orderIndex;
                for (int lineNumber = 1; lineNumber <= linesEach; lineNumber++) {
                    lines.add(line(orderId, lineNumber));
                    lineCount++;
                }
            }
            for (int shipmentIndex = 1; shipmentIndex <= 3; shipmentIndex++) {
                long shipmentId = (customerId * 20_000L) + shipmentIndex;
                for (int eventIndex = 1; eventIndex <= 4; eventIndex++) {
                    shipmentEvents.add(shipmentEvent(shipmentId, eventIndex, now));
                    shipmentEventCount++;
                }
            }
            tickets.add(ticket(customerId, now));
            auditEvents.add(auditEvent(auditEventCount + 1, "CustomerEntity", customerId, now));
            auditEventCount++;
        }
        lines.finish();
        shipmentEvents.finish();
        tickets.finish();
        auditEvents.finish();

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
        product.unitPrice = BigDecimal.valueOf(1_000L + (productId * 100L), 2);
        product.stockQuantity = productId % 10 == 0 ? 8 : 500 - (int) productId;
        product.reservedQuantity = productId % 10 == 0 ? 5 : (int) (productId % 7);
        product.stockStatus = SampleDomainPolicies.stockStatus(product);
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
        order.orderAmount = BigDecimal.valueOf(10_000L + ((orderId % 700) * 100L), 2);
        order.currencyCode = "USD";
        order.orderType = orderId % 4 == 0 ? "EXPRESS" : "STANDARD";
        order.status = orderId % 37 == 0 ? "COMPLETED" : orderId % 6 == 0 ? "PAID" : "NEW";
        order.lineCount = linesEach;
        order.priorityScore = SampleDomainPolicies.orderPriority(order);
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
        line.unitPrice = BigDecimal.valueOf(1_000L + (productId * 100L), 2);
        line.lineTotal = line.unitPrice.multiply(BigDecimal.valueOf(line.quantity));
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
        shipment.riskScore = SampleDomainPolicies.shipmentRisk(shipment.shipmentStatus);
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

    private <T, ID> DurableBatch<T, ID> batch(
            String surface,
            Function<Collection<T>, List<WriteReceipt<T, ID>>> writer
    ) {
        return new DurableBatch<>(surface, writer);
    }

    private int requireInRange(String parameter, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    parameter + " must be between " + min + " and " + max + "; received " + value
            );
        }
        return value;
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

    private final class DurableBatch<T, ID> {
        private final String surface;
        private final Function<Collection<T>, List<WriteReceipt<T, ID>>> writer;
        private final ArrayList<T> buffer = new ArrayList<>(WRITE_BATCH_SIZE);
        private final ArrayList<WriteReceipt<?, ?>> pending = new ArrayList<>(MAX_PENDING_RECEIPTS);

        private DurableBatch(String surface, Function<Collection<T>, List<WriteReceipt<T, ID>>> writer) {
            this.surface = surface;
            this.writer = writer;
        }

        private void add(T entity) {
            buffer.add(entity);
            if (buffer.size() >= WRITE_BATCH_SIZE) {
                flush();
            }
        }

        private void finish() {
            flush();
            awaitPending();
        }

        private void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            pending.addAll(writer.apply(List.copyOf(buffer)));
            buffer.clear();
            if (pending.size() >= MAX_PENDING_RECEIPTS) {
                awaitPending();
            }
        }

        private void awaitPending() {
            if (pending.isEmpty()) {
                return;
            }
            if (!cacheDatabase.awaitDurable(pending, DURABILITY_TIMEOUT)) {
                throw new IllegalStateException(
                        "Seed write-behind did not become durable for " + surface
                                + " within " + DURABILITY_TIMEOUT.toSeconds() + " seconds"
                );
            }
            pending.clear();
        }
    }
}
