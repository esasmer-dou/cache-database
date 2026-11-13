package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.protection.*;
import java.util.*;

/** Pure canonical layout identity, not an inventory, owner claim or runtime closure certificate. */
public final class RedisInitialSourceLayout {
    private RedisInitialSourceLayout() {}
    public static void requireBinding(EntityMetadata<?, ?> metadata, RedisKeyStrategy keys,
            QueryIndexConfig config, TenantPurgeProtocol.Binding binding) {
        if (!layoutToken(metadata,keys,config).equals(binding.layoutToken()))
            throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
    }
    public static String layoutToken(EntityMetadata<?, ?> metadata, RedisKeyStrategy keys, QueryIndexConfig config) {
        if (!metadata.compositeExactIndexes().isEmpty() || !metadata.compositeSortColumns().isEmpty()
                || !metadata.partitionedSortIndexes().isEmpty())
            throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        var layout = new RedisPurgeIndexLayout(metadata, keys, config, Map.of(), List.of());
        var descriptor = new RedisPurgeTarget(layout, List.of(), metadata.tenantFieldName(), false, true, ignored -> {});
        return RedisIdentityPurgeParticipant.layoutFingerprint(List.of(descriptor));
    }
}
