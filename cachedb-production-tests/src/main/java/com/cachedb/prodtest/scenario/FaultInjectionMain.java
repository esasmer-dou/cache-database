package com.reactor.cachedb.prodtest.scenario;

public final class FaultInjectionMain {

    private FaultInjectionMain() {
    }

    public static void main(String[] args) throws Exception {
        FaultInjectionSuiteReport report = new FaultInjectionSuiteRunner().run();
        System.out.println("fault injection scenarios=" + report.scenarioCount()
                + ", successful=" + report.successfulScenarios()
                + ", allSuccessful=" + report.allSuccessful());
    }
}
