package com.reactor.cachedb.integration;

import com.reactor.cachedb.examples.demo.DemoScenarioLevel;
import com.reactor.cachedb.examples.demo.DemoScenarioTuning;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoScenarioTuningTest {

    @Test
    void shouldApplyDemoScenarioOverrides() {
        Properties properties = new Properties();
        properties.setProperty("cachedb.demo.seed.customers", "12");
        properties.setProperty("cachedb.demo.seed.products", "18");
        properties.setProperty("cachedb.demo.cache.hotEntityLimit", "256");
        properties.setProperty("cachedb.demo.ui.autoRefreshMillis", "1500");
        properties.setProperty("cachedb.demo.load.medium.readers", "11");
        properties.setProperty("cachedb.demo.load.medium.writers", "5");
        properties.setProperty("cachedb.demo.load.medium.readerPauseMillis", "13");
        properties.setProperty("cachedb.demo.load.medium.writerPauseMillis", "21");
        properties.setProperty("cachedb.demo.read.topCustomerOrderLimit", "220");
        properties.setProperty("cachedb.demo.seed.orderLines", "4400");
        properties.setProperty("cachedb.demo.write.maxOrderLineQuantity", "22");
        properties.setProperty("cachedb.demo.write.maxOrderLineItemCount", "240");

        DemoScenarioTuning tuning = DemoScenarioTuning.fromProperties(properties, "cachedb.demo");

        assertEquals(12, tuning.customerSeedCount());
        assertEquals(18, tuning.productSeedCount());
        assertEquals(256, tuning.cacheHotEntityLimit());
        assertEquals(1500, tuning.uiAutoRefreshMillis());
        assertEquals(11, tuning.profile(DemoScenarioLevel.MEDIUM).readerThreads());
        assertEquals(5, tuning.profile(DemoScenarioLevel.MEDIUM).writerThreads());
        assertEquals(13L, tuning.profile(DemoScenarioLevel.MEDIUM).readerPauseMillis());
        assertEquals(21L, tuning.profile(DemoScenarioLevel.MEDIUM).writerPauseMillis());
        assertEquals(220, tuning.topCustomerOrderReadLimit());
        assertEquals(4400, tuning.orderLineSeedCount());
        assertEquals(22, tuning.maxOrderLineQuantity());
        assertEquals(240, tuning.maxOrderLineItemCount());
    }

    @Test
    void shouldDefaultToMediumSizedSeedAndRealisticLoadProfiles() {
        DemoScenarioTuning tuning = DemoScenarioTuning.defaults();

        assertTrue(tuning.customerSeedCount() > 50);
        assertTrue(tuning.productSeedCount() > 50);
        assertTrue(tuning.cartSeedCount() > 50);
        assertTrue(tuning.orderSeedCount() > 50);
        assertTrue(tuning.orderLineSeedCount() > 1_000);
        assertTrue(
                tuning.customerSeedCount()
                        + tuning.productSeedCount()
                        + tuning.cartSeedCount()
                        + tuning.orderSeedCount()
                        + tuning.orderLineSeedCount() > 10_000
        );

        assertTrue(tuning.profile(DemoScenarioLevel.LOW).readerThreads() < tuning.profile(DemoScenarioLevel.MEDIUM).readerThreads());
        assertTrue(tuning.profile(DemoScenarioLevel.MEDIUM).readerThreads() < tuning.profile(DemoScenarioLevel.HIGH).readerThreads());
        assertTrue(tuning.profile(DemoScenarioLevel.LOW).writerThreads() < tuning.profile(DemoScenarioLevel.MEDIUM).writerThreads());
        assertTrue(tuning.profile(DemoScenarioLevel.MEDIUM).writerThreads() < tuning.profile(DemoScenarioLevel.HIGH).writerThreads());
        assertTrue(tuning.profile(DemoScenarioLevel.LOW).readerPauseMillis() > tuning.profile(DemoScenarioLevel.MEDIUM).readerPauseMillis());
        assertTrue(tuning.profile(DemoScenarioLevel.MEDIUM).readerPauseMillis() > tuning.profile(DemoScenarioLevel.HIGH).readerPauseMillis());
        assertTrue(tuning.profile(DemoScenarioLevel.LOW).writerPauseMillis() > tuning.profile(DemoScenarioLevel.MEDIUM).writerPauseMillis());
        assertTrue(tuning.profile(DemoScenarioLevel.MEDIUM).writerPauseMillis() > tuning.profile(DemoScenarioLevel.HIGH).writerPauseMillis());
    }
}
