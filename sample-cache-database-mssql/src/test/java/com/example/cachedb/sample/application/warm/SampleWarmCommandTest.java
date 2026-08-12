package com.example.cachedb.sample.application.warm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SampleWarmCommandTest {

    @Test
    void keepsRouteSpecificBoundsInsideTheDurableCommand() {
        SampleWarmCommand command = SampleWarmCommand.customerOrders(42L, 1_000, true, false);

        assertEquals(SampleWarmCommand.Route.CUSTOMER_ORDERS, command.route());
        assertEquals(1_000, command.limit());
        assertThrows(IllegalArgumentException.class,
                () -> SampleWarmCommand.activeCustomers(101, false));
        assertThrows(IllegalArgumentException.class,
                () -> SampleWarmCommand.reportsByType("SALES", 51, false));
    }

    @Test
    void rejectsInvalidBusinessArgumentsBeforeQueueAdmission() {
        assertThrows(IllegalArgumentException.class,
                () -> SampleWarmCommand.customerOrders(0L, 10, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> SampleWarmCommand.highlightedOrders(-1.0d, 10, false));
        assertThrows(IllegalArgumentException.class,
                () -> SampleWarmCommand.highlightedOrders(Double.NaN, 10, false));
        assertThrows(IllegalArgumentException.class,
                () -> SampleWarmCommand.reportsByType(" ", 10, false));
    }
}
