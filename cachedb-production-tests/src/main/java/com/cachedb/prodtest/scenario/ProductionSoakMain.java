package com.reactor.cachedb.prodtest.scenario;

public final class ProductionSoakMain {

    private ProductionSoakMain() {
    }

    public static void main(String[] args) throws Exception {
        EcommerceScenarioProfile profile = ScenarioCatalog.byName(System.getProperty("cachedb.prod.soak.scenario", "campaign-push-spike"));
        double scaleFactor = Double.parseDouble(System.getProperty("cachedb.prod.soak.scaleFactor", "0.02"));
        int iterations = Integer.getInteger("cachedb.prod.soak.iterations", 3);
        ProductionSoakReport report = new ProductionSoakRunner().run(profile, scaleFactor, iterations);
        System.out.println("soak scenario=" + report.scenarioName()
                + " iterations=" + report.iterationCount()
                + " avgTps=" + report.averageTps()
                + " maxBacklog=" + report.maxBacklog());
    }
}
