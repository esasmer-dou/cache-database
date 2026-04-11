package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public final class EcommerceProductionScenarioMain {

    private EcommerceProductionScenarioMain() {
    }

    public static void main(String[] args) throws Exception {
        EcommerceProductionScenarioRunner runner = new EcommerceProductionScenarioRunner();
        double scaleFactor = Double.parseDouble(System.getProperty("cachedb.prod.scaleFactor", "0.02"));
        String scenarioName = System.getProperty("cachedb.prod.scenario", "");
        List<EcommerceScenarioProfile> profiles = scenarioName == null || scenarioName.isBlank()
                ? ScenarioCatalog.all()
                : List.of(ScenarioCatalog.byName(scenarioName));

        for (EcommerceScenarioProfile profile : profiles) {
            ScenarioReport report = runner.run(profile, scaleFactor);
            System.out.println(profile.name()
                    + " ops=" + report.executedOperations()
                    + " reads=" + report.readOperations()
                    + " writes=" + report.writeOperations()
                    + " failures=" + report.failedOperations()
                    + " backlog=" + report.writeBehindStreamLength()
                    + " avgLatencyMicros=" + report.averageLatencyMicros());
        }
    }
}
