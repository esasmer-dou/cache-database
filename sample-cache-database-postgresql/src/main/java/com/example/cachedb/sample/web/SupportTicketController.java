package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.domain.SupportTicketEntityCacheBinding;
import com.reactor.cachedb.core.api.EntityRepository;
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

    private final EntityRepository<SupportTicketEntity, Long> ticketRepository;

    public SupportTicketController(EntityRepository<SupportTicketEntity, Long> ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @GetMapping("/open")
    public List<SupportTicketEntity> open(@RequestParam(defaultValue = "25") int limit) {
        return SupportTicketEntityCacheBinding.openTickets(ticketRepository, clamp(limit, 1, 1_000));
    }

    @GetMapping("/{ticketId}")
    public SupportTicketEntity detail(@PathVariable long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found in active set: " + ticketId));
    }

    @PostMapping
    public SupportTicketEntity create(@RequestBody CreateTicketRequest request) {
        long now = Instant.now().getEpochSecond();
        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.ticketId = request.ticketId();
        ticket.customerId = request.customerId();
        ticket.priority = request.priority() == null ? "NORMAL" : request.priority();
        ticket.status = request.status() == null ? "OPEN" : request.status();
        ticket.subject = request.subject() == null ? "Sample support case" : request.subject();
        ticket.openedAt = now;
        ticket.updatedAt = now;
        return ticketRepository.save(ticket);
    }

    @PatchMapping("/{ticketId}/status")
    public SupportTicketEntity updateStatus(@PathVariable long ticketId, @RequestBody UpdateTicketStatusRequest request) {
        SupportTicketEntity ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found in active set: " + ticketId));
        ticket.status = request.status();
        ticket.priority = request.priority() == null ? ticket.priority : request.priority();
        ticket.updatedAt = Instant.now().getEpochSecond();
        return ticketRepository.save(ticket);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record CreateTicketRequest(Long ticketId, Long customerId, String priority, String status, String subject) {
    }

    public record UpdateTicketStatusRequest(String status, String priority) {
    }
}
