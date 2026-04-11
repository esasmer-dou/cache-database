package com.reactor.cachedb.prodtest.scenario;

public final class ProductionCertificationMain {

    private ProductionCertificationMain() {
    }

    public static void main(String[] args) throws Exception {
        ProductionCertificationReport report = new ProductionCertificationRunner().run();
        System.out.println("Production certification completed: passed=" + report.passed());
        System.out.println("Benchmark scenario=" + report.benchmarkReport().scenarioName()
                + ", tps=" + report.benchmarkReport().achievedTransactionsPerSecond()
                + ", backlog=" + report.benchmarkReport().writeBehindStreamLength());
    }
}
