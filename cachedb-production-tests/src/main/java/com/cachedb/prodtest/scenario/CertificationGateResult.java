package com.reactor.cachedb.prodtest.scenario;

public record CertificationGateResult(
        String gateName,
        boolean passed,
        String expected,
        String actual,
        String details
) {
}
