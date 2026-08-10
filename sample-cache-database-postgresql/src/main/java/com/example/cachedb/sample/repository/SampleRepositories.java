package com.example.cachedb.sample.repository;

import org.springframework.stereotype.Component;

/** Groups repositories for infrastructure workflows such as demo seeding. */
@Component
public record SampleRepositories(
        CustomerRepository customers,
        OrderRepository orders,
        OrderLineRepository orderLines,
        ProductRepository products,
        SupportTicketRepository supportTickets,
        ShipmentRepository shipments,
        ShipmentEventRepository shipmentEvents,
        ReportJobRepository reportJobs,
        AuditEventRepository auditEvents
) {
}
