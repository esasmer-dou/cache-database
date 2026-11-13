package com.reactor.cachedb.core.protection;

import java.time.Instant;
import java.util.UUID;

/** Bounded, nonblocking sink. Deduplicate by operation/revision/event; events may be redelivered. */
@FunctionalInterface
public interface CachePurgeAuditListener {
    void onEvent(Event event);
    record Event(UUID operationId, ProtectionKey key, long revision, String event,
            String operatorToken, Instant at, ProtectionException.Code failure,
            long redisRows, long redisKeys, long sourceRows) {
        public Event {
            java.util.Objects.requireNonNull(operationId); java.util.Objects.requireNonNull(key);
            java.util.Objects.requireNonNull(at); ProtectionKey.token(operatorToken);
            if (revision < -1 || event == null || !event.matches("[A-Z_]{1,64}")
                    || redisRows < -1 || redisKeys < -1 || sourceRows < -1)
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        }
    }
}
