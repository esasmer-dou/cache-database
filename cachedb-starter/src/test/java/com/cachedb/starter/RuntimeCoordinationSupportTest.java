package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.RuntimeCoordinationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCoordinationSupportTest {

    @Test
    void shouldResolveConfiguredInstanceIdAndAppendItToConsumerNames() {
        RuntimeCoordinationConfig config = RuntimeCoordinationConfig.builder()
                .instanceId("pod-A_01")
                .appendInstanceIdToConsumerNames(true)
                .build();

        String instanceId = RuntimeCoordinationSupport.resolveInstanceId(config);
        String consumerPrefix = RuntimeCoordinationSupport.consumerNamePrefix("cachedb-worker", config, instanceId);

        assertEquals("pod-a_01", instanceId);
        assertEquals("cachedb-worker-pod-a_01", consumerPrefix);
    }

    @Test
    void shouldKeepConsumerPrefixStableWhenInstanceSuffixingIsDisabled() {
        RuntimeCoordinationConfig config = RuntimeCoordinationConfig.builder()
                .instanceId("pod-B")
                .appendInstanceIdToConsumerNames(false)
                .build();

        String instanceId = RuntimeCoordinationSupport.resolveInstanceId(config);
        String consumerPrefix = RuntimeCoordinationSupport.consumerNamePrefix("cachedb-worker", config, instanceId);

        assertEquals("pod-b", instanceId);
        assertEquals("cachedb-worker", consumerPrefix);
        assertFalse(consumerPrefix.contains(instanceId));
    }

    @Test
    void shouldBuildLeaderLeaseWithNamespacedKeyWhenEnabled() {
        RuntimeCoordinationConfig config = RuntimeCoordinationConfig.builder()
                .instanceId("pod-c")
                .leaderLeaseEnabled(true)
                .leaderLeaseSegment("coordination:test")
                .build();

        assertTrue(RuntimeCoordinationSupport.resolveInstanceId(config).startsWith("pod-c"));
    }
}
