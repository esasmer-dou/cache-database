package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.ReadThroughConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSafetyConfigTest {

    @Test
    void durableStateMustNotExpireOrBeTrimmed() {
        assertThrows(IllegalArgumentException.class, () -> RedisGuardrailConfig.builder()
                .compactionPayloadTtlSeconds(60)
                .build());
        assertThrows(IllegalArgumentException.class, () -> RedisGuardrailConfig.builder()
                .compactionPendingTtlSeconds(60)
                .build());
        assertThrows(IllegalArgumentException.class, () -> RedisGuardrailConfig.builder()
                .versionKeyTtlSeconds(60)
                .build());
        assertThrows(IllegalArgumentException.class, () -> WriteBehindConfig.builder()
                .compactionMaxLength(1_000)
                .build());
    }

    @Test
    void expensiveStringIndexesMustBeExplicitlyEnabled() {
        QueryIndexConfig config = QueryIndexConfig.defaults();

        assertFalse(config.prefixIndexEnabled());
        assertFalse(config.textIndexEnabled());
        assertThrows(IllegalArgumentException.class, () -> QueryIndexConfig.builder()
                .maxMaterializedCandidateIds(0)
                .build());
    }

    @Test
    void readThroughQueryTimeoutMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> ReadThroughConfig.builder()
                .queryTimeoutSeconds(0)
                .build());
        assertThrows(IllegalArgumentException.class, () -> WriteBehindConfig.builder()
                .statementTimeoutSeconds(0)
                .build());
    }
}
