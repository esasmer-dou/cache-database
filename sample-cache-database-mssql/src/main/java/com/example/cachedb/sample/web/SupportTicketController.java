package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.support.SupportTicketApplicationService;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class SupportTicketController {

    private final SupportTicketApplicationService tickets;

    public SupportTicketController(SupportTicketApplicationService tickets) {
        this.tickets = tickets;
    }

    @GetMapping("/open")
    public List<SupportTicketEntity> open(@RequestParam(defaultValue = "25") int limit) {
        return tickets.open(ApiLimits.requireInRange("limit", limit, 1, 50));
    }

    @GetMapping("/{ticketId}")
    public SupportTicketEntity detail(@PathVariable long ticketId) {
        return tickets.detail(ticketId);
    }

    @PostMapping
    public ResponseEntity<WriteAccepted<SupportTicketEntity>> create(
            @Valid @RequestBody CreateTicketRequest request
    ) {
        var receipt = tickets.create(new SupportTicketApplicationService.CreateTicket(
                request.ticketId(), request.customerId(), request.priority(), request.status(), request.subject()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("CREATE", "SupportTicketEntity", receipt));
    }

    @PatchMapping("/{ticketId}/status")
    public ResponseEntity<WriteAccepted<SupportTicketEntity>> updateStatus(
            @PathVariable long ticketId,
            @Valid @RequestBody UpdateTicketStatusRequest request
    ) {
        var receipt = tickets.updateStatus(ticketId, new SupportTicketApplicationService.UpdateTicketStatus(
                request.status(), request.priority()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("UPDATE", "SupportTicketEntity", receipt));
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
