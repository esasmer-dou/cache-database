package com.reactor.cachedb.prodtest.scenario;

public final class CrashReplayChaosMain {

    private CrashReplayChaosMain() {
    }

    public static void main(String[] args) throws Exception {
        CrashReplayChaosSuiteReport report = new CrashReplayChaosSuiteRunner().run();
        System.out.println("crash replay chaos scenarios=" + report.scenarioCount()
                + ", successful=" + report.successfulScenarios()
                + ", allSuccessful=" + report.allSuccessful());
    }
}
