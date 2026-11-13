package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.ProtectionKey;
import java.util.*;
import java.util.function.Consumer;

/** Runtime-owned native store description. No namespace scans or arbitrary key locators. */
public final class RedisPurgeTarget {
    public enum PageKind { LIST, ZSET }
    public record Page(int number, PageKind kind) {
        public Page {
            if (number < 0 || number > 1_000_000 || kind == null) throw RedisIdentityPurgeParticipant.incomplete();
        }
    }
    record Action(String key, String type) {
        @Override public String toString() { return "PurgeAction[redacted]"; }
    }
    final RedisPurgeIndexLayout indexes;
    final List<Page> pages;
    final String tenantColumn;
    final boolean projection, identityPreserving;
    final Consumer<String> invalidateLocal;
    final String fingerprint;

    RedisPurgeTarget(RedisPurgeIndexLayout indexes, List<Page> pages, String tenantColumn,
            boolean projection, boolean identityPreserving, Consumer<String> invalidateLocal) {
        this.indexes = Objects.requireNonNull(indexes);
        if (pages == null || pages.size() > 128 || new HashSet<>(pages).size() != pages.size())
            throw RedisIdentityPurgeParticipant.incomplete();
        var numbers = new HashSet<Integer>();
        for (var page : pages) if (!numbers.add(page.number())) throw RedisIdentityPurgeParticipant.incomplete();
        this.pages = List.copyOf(pages); this.tenantColumn = tenantColumn;
        this.projection = projection; this.identityPreserving = identityPreserving;
        this.invalidateLocal = Objects.requireNonNull(invalidateLocal);
        var signature = new StringBuilder(indexes.fingerprint);
        RedisPurgeIndexLayout.add(signature, tenantColumn == null ? "" : tenantColumn);
        RedisPurgeIndexLayout.add(signature, projection + ":" + identityPreserving);
        pages.stream().sorted(Comparator.comparingInt(Page::number)).forEach(page ->
                RedisPurgeIndexLayout.add(signature, page.number() + ":" + page.kind()));
        fingerprint = ProtectionKey.hash(signature.toString());
    }

    public String namespace() { return indexes.metadata.redisNamespace(); }
    public String fingerprint() { return fingerprint; }

    List<Action> ownedKeys(String id) {
        var keys = indexes.keys; String ns = namespace();
        var result = new ArrayList<Action>();
        result.add(new Action(keys.entityKey(ns, id), "string"));
        result.add(new Action(keys.tombstoneKey(ns, id), "string"));
        result.add(new Action(keys.indexMetaKey(ns, id), "hash"));
        if (!projection) {
            result.add(new Action(keys.versionKey(ns, id), "string"));
            result.add(new Action(keys.versionKey(ns, id) + ":tenant", "string"));
            result.add(new Action(keys.durabilityKey(ns, id), "string"));
            result.add(new Action(keys.tenantHotOwnerKey(ns, id), "string"));
        }
        return List.copyOf(result);
    }

    List<Action> memberships(String id, String tenant, Map<String, String> prior) {
        var result = new ArrayList<>(indexes.removals(id, tenant, prior, projection));
        result.add(new Action(indexes.keys.hotSetKey(namespace()), "zset"));
        for (var page : pages)
            result.add(new Action(indexes.keys.pageKey(namespace(), page.number()),
                    page.kind() == PageKind.LIST ? "list" : "zset"));
        return List.copyOf(result);
    }
}
