package com.reactor.cachedb.spring.boot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheDistributedJobContractTest {

    @Test
    void typedHandlerDerivesRouteAndArgumentTypeFromOneDefinition() {
        CacheDistributedJobDefinition<String> definition =
                CacheDistributedJobDefinition.of("test.route", String.class);
        CacheDistributedJobHandler.Typed<String> handler = new CacheDistributedJobHandler.Typed<>() {
            @Override
            public CacheDistributedJobDefinition<String> definition() {
                return definition;
            }

            @Override
            public Object execute(String arguments, CacheDistributedJobContext context) {
                return arguments;
            }
        };

        assertEquals("test.route", handler.route());
        assertEquals(String.class, handler.argumentType());
    }

    @Test
    void structuredProgressRejectsUnboundedPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> CacheDistributedJobProgress.phase("bad phase", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CacheDistributedJobProgress("RUNNING", 1, 101, "", null));
        assertThrows(IllegalArgumentException.class,
                () -> CacheDistributedJobProgress.phase("RUNNING", 1)
                        .withAttribute("route", "x".repeat(257)));

        CacheDistributedJobProgress completed = CacheDistributedJobProgress.completed(2)
                .withAttribute("route", "orders");
        assertEquals(100, completed.percent());
        assertEquals("orders", completed.attributes().get("route"));
    }
}
