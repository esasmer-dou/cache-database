package com.reactor.cachedb.prodtest.scenario;

public final class ProductionGateLadderMain {

    private ProductionGateLadderMain() {
    }

    public static void main(String[] args) throws Exception {
        ProductionGateLadderReport report = new ProductionGateLadderRunner().run();
        System.out.println("production gate ladder passed=" + report.passed()
                + ", profiles=" + report.profileCount());
    }
}
