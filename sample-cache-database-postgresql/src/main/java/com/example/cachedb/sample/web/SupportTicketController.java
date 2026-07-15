package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.service.DurableReferenceGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class SupportTicketController {

    private final GeneratedCacheModule.Scope domain;
    private final DurableReferenceGuard durableReferenceGuard;

    public SupportTicketController(
            GeneratedCacheModule.Scope domain,
            DurableReferenceGuard durableReferenceGuard
    ) {
        this.domain = domain;
        this.durableReferenceGuard = durableReferenceGuard;
    }

    @GetMapping("/open")
    public List<SupportTicketEntity> open(@RequestParam(defaultValue = "25") int limit) {
        return domain.supportTickets().queries()
                .openTickets(ApiLimits.requireInRange("limit", limit, 1, 50));
    }

    @GetMapping("/{ticketId}")
    public SupportTicketEntity detail(@PathVariable long ticketId) {
        return domain.supportTickets().findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found in active set: " + ticketId));
    }

    @PostMapping
    public ResponseEntity<WriteAccepted<SupportTicketEntity>> create(
            @Valid @RequestBody CreateTicketRequest request
    ) {
        durableReferenceGuard.requireCustomer(request.customerId());
        long now = Instant.now().getEpochSecond();
        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.ticketId = request.ticketId();
        ticket.customerId = request.customerId();
        ticket.priority = request.priority() == null ? "NORMAL" : request.priority();
        ticket.status = request.status() == null ? "OPEN" : request.status();
        ticket.subject = request.subject() == null ? "Sample support case" : request.subject();
        ticket.openedAt = now;
        ticket.updatedAt = now;
        SupportTicketEntity saved = domain.supportTickets().save(ticket);
        return ResponseEntity.accepted().body(WriteAccepted.of("CREATE", "SupportTicketEntity", saved.ticketId, saved));
    }

    @PatchMapping("/{ticketId}/status")
    public ResponseEntity<WriteAccepted<SupportTicketEntity>> updateStatus(
            @PathVariable long ticketId,
            @Valid @RequestBody UpdateTicketStatusRequest request
    ) {
        SupportTicketEntity ticket = domain.supportTickets().findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found in active set: " + ticketId));
        ticket.status = request.status();
        ticket.priority = request.priority() == null ? ticket.priority : request.priority();
        ticket.updatedAt = Instant.now().getEpochSecond();
        SupportTicketEntity saved = domain.supportTickets().save(ticket);
        return ResponseEntity.accepted().body(WriteAccepted.of("UPDATE", "SupportTicketEntity", saved.ticketId, saved));
    }

    public record CreateTicketRequest(
            @NotNull @Positive Long ticketId,
            @NotNull @Positive Long customerId,
            @Size(max = 32) String priority,
            @Size(max = 32) String status,
            @NotBlank @Size(max = 512) String subject
    ) {
    }

    public record UpdateTicketStatusRequest(
            @NotBlank @Size(max = 32) String status,
            @Size(max = 32) String priority
    ) {
    }
}
