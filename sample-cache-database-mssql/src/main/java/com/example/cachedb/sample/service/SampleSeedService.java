package com.example.cachedb.sample.service;

import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderLineEntity;
import com.example.cachedb.sample.domain.ProductEntity;
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
    private final JdbcTemplate jdbcTemplate;

    public SampleSeedService(
            EntityRepository<CustomerEntity, Long> customerRepository,
            EntityRepository<ProductEntity, Long> productRepository,
            EntityRepository<OrderEntity, Long> orderRepository,
            EntityRepository<OrderLineEntity, Long> lineRepository,
            EntityRepository<SupportTicketEntity, Long> ticketRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.ticketRepository = ticketRepository;
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
        for (long customerId = 1; customerId <= customers; customerId++) {
            for (int index = 1; index <= ordersEach; index++) {
                long orderId = (customerId * 10_000L) + index;
                OrderEntity order = order(orderId, customerId, now - (index * 3_600L), linesEach);
                orderRepository.save(order);
                orderCount++;
            }
        }

        waitForRows("sample_orders", orderCount);

        for (long customerId = 1; customerId <= customers; customerId++) {
            for (int orderIndex = 1; orderIndex <= ordersEach; orderIndex++) {
                long orderId = (customerId * 10_000L) + orderIndex;
                for (int lineNumber = 1; lineNumber <= linesEach; lineNumber++) {
                    lineRepository.save(line(orderId, lineNumber));
                    lineCount++;
                }
            }
            ticketRepository.save(ticket(customerId, now));
        }

        return new SeedResult(customers, 50, orderCount, lineCount, customers);
    }

    private ProductEntity product(long productId) {
        ProductEntity product = new ProductEntity();
        product.productId = productId;
        product.sku = "SKU-" + productId;
        product.productName = "Sample Product " + productId;
        product.category = productId % 3 == 0 ? "electronics" : productId % 3 == 1 ? "grocery" : "home";
        product.activeStatus = "ACTIVE";
        product.unitPrice = 10.0 + productId;
        product.stockQuantity = 500 - (int) productId;
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
        order.orderDate = orderDate;
        order.orderAmount = 100.0 + (orderId % 700);
        order.currencyCode = "USD";
        order.orderType = orderId % 4 == 0 ? "EXPRESS" : "STANDARD";
        order.status = orderId % 6 == 0 ? "PAID" : "NEW";
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

    public record SeedResult(int customers, int products, long orders, long orderLines, int supportTickets) {
    }
}
