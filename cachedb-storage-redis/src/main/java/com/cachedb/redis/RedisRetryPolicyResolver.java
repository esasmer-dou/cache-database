package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.WriteRetryPolicy;
import com.reactor.cachedb.core.config.WriteRetryPolicyOverride;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;

import java.util.List;

public final class RedisRetryPolicyResolver {

    private RedisRetryPolicyResolver() {
    }

    public static WriteRetryPolicy resolve(
            int defaultRetries,
            long defaultBackoffMillis,
            List<WriteRetryPolicyOverride> overrides,
            QueuedWriteOperation operation
    ) {
        WriteRetryPolicy resolved = WriteRetryPolicy.of(defaultRetries, defaultBackoffMillis);
        int bestSpecificity = -1;
        for (WriteRetryPolicyOverride override : overrides) {
            if (!override.matches(operation.entityName(), operation.type())) {
                continue;
            }
            int specificity = override.specificity();
            if (specificity > bestSpecificity) {
                resolved = WriteRetryPolicy.of(override.maxRetries(), override.backoffMillis());
                bestSpecificity = specificity;
            }
        }
        return resolved;
    }
}
