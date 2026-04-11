package com.reactor.cachedb.prodtest.scenario;

public final class ProductionSoakPlanMain {

    private ProductionSoakPlanMain() {
    }

    public static void main(String[] args) throws Exception {
        ProductionSoakPlanReport report = new ProductionSoakPlanRunner().run();
        System.out.println("production soak plan runs=" + report.runCount());
    }
}
