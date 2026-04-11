package com.reactor.cachedb.prodtest.scenario;

public final class MultiInstanceCoordinationSmokeMain {

    private MultiInstanceCoordinationSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        MultiInstanceCoordinationSmokeReport report = new MultiInstanceCoordinationSmokeRunner().run();
        System.out.println("multi-instance coordination smoke passed=" + report.allSuccessful());
        System.out.println("observed consumers=" + report.observedConsumerNames());
        System.out.println("leader failover=" + report.initialLeaderInstanceId() + " -> " + report.failoverLeaderInstanceId());
        System.out.println("claimant claimed count=" + report.claimantClaimedCount());
    }
}
