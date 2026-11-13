package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.protection.PurgeWorkItem;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.jdbc.JdbcPurgeScanJournal;
import com.reactor.cachedb.redis.RedisIdentityPurgeParticipant;
import java.util.List;
import java.util.Objects;

/** Admin-only adapter over the existing sealed journal. Never queries or deletes business source rows. */
public final class JdbcRedisPurgeInventory implements RedisIdentityPurgeParticipant.Inventory {
    private final JdbcPurgeScanJournal journal;
    public JdbcRedisPurgeInventory(JdbcPurgeScanJournal journal) { this.journal = Objects.requireNonNull(journal); }
    public boolean sealed(Lease lease) { return journal.discoveryComplete(lease); }
    public List<PurgeWorkItem> page(Lease lease, String after, int limit, int maxBytes) {
        return journal.page(lease, PurgeWorkItem.Kind.ID, after, limit, maxBytes);
    }
}
