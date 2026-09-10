package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "sample_support_tickets", redisNamespace = "sample-support-tickets")
public class SupportTicketEntity {

    @CacheId(column = "ticket_id")
    public Long ticketId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("priority")
    public String priority;

    @CacheColumn("status")
    public String status;

    @CacheColumn("subject")
    public String subject;

    @CacheColumn("opened_at")
    public Long openedAt;

    @CacheColumn("updated_at")
    public Long updatedAt;

    public SupportTicketEntity() {
    }
}
