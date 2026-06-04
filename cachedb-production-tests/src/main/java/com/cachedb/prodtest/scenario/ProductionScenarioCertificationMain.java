package com.reactor.cachedb.prodtest.scenario;

public final class ProductionScenarioCertificationMain {

    private ProductionScenarioCertificationMain() {
    }

    public static void main(String[] args) throws Exception {
        ProductionScenarioCertificationReport report = new ProductionScenarioCertificationRunner().run();
        System.out.println("Production scenario certification completed: passed=" + report.passed());
        System.out.println("Gate count=" + report.gates().size());
    }
}
