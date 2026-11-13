package com.example.cachedb.sample.audit;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.SourceRepository;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.RetentionSweeper;

public record AuditStores(
        EntityRepository<EvictAuditNote, String> evict,
        EntityRepository<DeleteAuditNote, String> delete,
        SourceRepository<EvictAuditNote, String> evictSource,
        SourceRepository<DeleteAuditNote, String> deleteSource,
        DeleteAuditNotes data, RetentionSweeper sweeper, CacheDatabase database) {}
