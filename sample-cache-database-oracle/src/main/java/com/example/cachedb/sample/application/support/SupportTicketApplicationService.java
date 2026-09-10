package com.example.cachedb.sample.application.support;

import com.example.cachedb.sample.application.SampleHotLookups;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.repository.SupportTicketRepository;
import com.example.cachedb.sample.service.DurableReferenceGuard;
import com.reactor.cachedb.core.model.WriteReceipt;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class SupportTicketApplicationService {

    private final SupportTicketRepository tickets;
    private final DurableReferenceGuard references;
    private final Clock clock;

    public SupportTicketApplicationService(
            SupportTicketRepository tickets,
            DurableReferenceGuard references,
            Clock clock
    ) {
        this.tickets = tickets;
        this.references = references;
        this.clock = clock;
    }

    public List<SupportTicketEntity> open(int limit) {
        return tickets.open(limit).completeItems();
    }

    public SupportTicketEntity detail(long ticketId) {
        return SampleHotLookups.require("Ticket", ticketId, tickets.detail(ticketId));
    }

    public WriteReceipt<SupportTicketEntity, Long> create(CreateTicket command) {
        DurableReferenceGuard.DurableReference customer = references.requireCustomer(command.customerId());
        long now = Instant.now(clock).getEpochSecond();
        SupportTicketEntity entity = new SupportTicketEntity();
        entity.ticketId = command.ticketId();
        entity.customerId = command.customerId();
        entity.priority = defaultText(command.priority(), "NORMAL");
        entity.status = defaultText(command.status(), "OPEN");
        entity.subject = defaultText(command.subject(), "Sample support case");
        entity.openedAt = now;
        entity.updatedAt = now;
        return customer.save(tickets, entity);
    }

    public WriteReceipt<SupportTicketEntity, Long> updateStatus(long ticketId, UpdateTicketStatus command) {
        return tickets.updateHot(ticketId, entity -> {
            entity.status = command.status();
            entity.priority = command.priority() == null ? entity.priority : command.priority();
            entity.updatedAt = Instant.now(clock).getEpochSecond();
            return entity;
        });
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record CreateTicket(
            Long ticketId,
            Long customerId,
            String priority,
            String status,
            String subject
    ) {
    }

    public record UpdateTicketStatus(String status, String priority) {
    }
}
