package com.reactor.cachedb.prodtest.scenario;

public final class RestartRecoverySuiteMain {

    private RestartRecoverySuiteMain() {
    }

    public static void main(String[] args) throws Exception {
        int cycles = Integer.getInteger("cachedb.prod.restart.cycles", 3);
        RestartRecoverySuiteReport report = new RestartRecoverySuiteRunner().run(cycles);
        System.out.println("restart suite cycles=" + report.cycleCount()
                + " successful=" + report.successfulCycles()
                + " allSuccessful=" + report.allSuccessful());
    }
}
