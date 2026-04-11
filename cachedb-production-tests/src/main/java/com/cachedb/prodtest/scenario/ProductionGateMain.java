package com.reactor.cachedb.prodtest.scenario;

public final class ProductionGateMain {

    private ProductionGateMain() {
    }

    public static void main(String[] args) throws Exception {
        ProductionGateReport report = new ProductionGateRunner().run();
        System.out.println("production gate overallStatus=" + report.overallStatus()
                + ", checks=" + report.checks().size());
    }
}
