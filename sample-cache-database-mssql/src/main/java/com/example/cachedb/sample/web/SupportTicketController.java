package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.domain.SupportTicketEntityCacheBinding;
import com.reactor.cachedb.core.api.EntityRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        return SupportTicketEntityCacheBinding.openTickets(ticketRepository, Math.max(1, Math.min(limit, 100)));
    }
}
