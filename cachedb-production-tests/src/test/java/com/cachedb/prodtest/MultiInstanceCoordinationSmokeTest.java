package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.MultiInstanceCoordinationSmokeReport;
import com.reactor.cachedb.prodtest.scenario.MultiInstanceCoordinationSmokeRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class MultiInstanceCoordinationSmokeTest {

    @Test
    void multiInstanceCoordinationSmokeShouldPass() throws Exception {
        MultiInstanceCoordinationSmokeReport report = new MultiInstanceCoordinationSmokeRunner().run();
        Assertions.assertTrue(report.uniqueConsumerNamesVerified());
        Assertions.assertTrue(report.leaderLeaseFailoverVerified());
        Assertions.assertTrue(report.writeBehindClaimFailoverVerified());
        Assertions.assertTrue(report.allSuccessful());
    }
}
