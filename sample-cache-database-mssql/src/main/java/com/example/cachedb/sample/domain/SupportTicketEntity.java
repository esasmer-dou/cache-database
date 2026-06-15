package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

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

    @CacheNamedQuery("openTickets")
    public static QuerySpec openTicketsQuery(int limit) {
        return QuerySpec.where(QueryFilter.eq("status", "OPEN"))
                .orderBy(QuerySort.desc("updated_at"), QuerySort.asc("ticket_id"))
                .limitTo(limit);
    }
}
