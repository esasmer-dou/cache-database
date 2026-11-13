package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.retention.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;

public final class RetentionSweeper {
    private final Map<Class<?>, RetentionTarget> targets;
    public RetentionSweeper(Collection<? extends RetentionTarget> targets) {
        if (targets == null || targets.size() > 1024) throw new IllegalArgumentException("At most 1024 retention targets");
        var map = new HashMap<Class<?>, RetentionTarget>();
        var scopes = new HashSet<String>();
        for (var target : targets)
            if (map.put(target.entityType(), target) != null || !scopes.add(target.scopeToken()))
                throw new IllegalArgumentException("Duplicate retention target");
        this.targets = Map.copyOf(map);
    }
    public RetentionSweepResult sweepOnce(Class<?> entityType, int maxRows) {
        if (!CacheTenantContext.isAdministrative()) throw new RetentionException(RetentionException.Code.ADMIN_REQUIRED);
        if (maxRows < 1 || maxRows > 10_000) throw new IllegalArgumentException("maxRows must be 1..10000");
        var target = targets.get(entityType);
        if (target == null) throw new IllegalArgumentException("Unregistered retention target");
        return target.sweep(maxRows);
    }
}
