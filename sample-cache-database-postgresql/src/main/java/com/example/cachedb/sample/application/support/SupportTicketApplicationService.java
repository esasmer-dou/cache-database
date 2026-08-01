package com.example.cachedb.sample.application.support;

import com.example.cachedb.sample.application.SampleEntityNotFoundException;
import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.service.DurableReferenceGuard;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.page.VersionedEntity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class SupportTicketApplicationService {

    private final GeneratedCacheModule.Scope domain;
    private final DurableReferenceGuard references;
    private final Clock clock;

    public SupportTicketApplicationService(
            GeneratedCacheModule.Scope domain,
            DurableReferenceGuard references,
            Clock clock
    ) {
        this.domain = domain;
        this.references = references;
        this.clock = clock;
    }

    public List<SupportTicketEntity> open(int limit) {
        return domain.supportTickets().queries().openTickets(limit);
    }

    public SupportTicketEntity detail(long ticketId) {
        return domain.supportTickets().findById(ticketId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Ticket", ticketId));
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
        return customer.save(domain.supportTickets().repository(), entity);
    }

    public WriteReceipt<SupportTicketEntity, Long> updateStatus(long ticketId, UpdateTicketStatus command) {
        VersionedEntity<SupportTicketEntity> current = domain.supportTickets().findVersionedById(ticketId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Ticket", ticketId));
        SupportTicketEntity entity = current.entity();
        entity.status = command.status();
        entity.priority = command.priority() == null ? entity.priority : command.priority();
        entity.updatedAt = Instant.now(clock).getEpochSecond();
        return domain.supportTickets().save(entity, current.version());
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
