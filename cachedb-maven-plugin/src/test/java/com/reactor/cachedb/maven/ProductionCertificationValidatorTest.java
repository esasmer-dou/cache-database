package com.reactor.cachedb.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionCertificationValidatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldAcceptCompleteApplicationEvidence() throws Exception {
        Path evidence = createEvidenceDirectory();

        ProductionCertificationValidator.Result result = new ProductionCertificationValidator()
                .validate(evidence, evidence.resolve("route-coverage.csv"));

        assertTrue(result.passed());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void shouldRejectMissingRouteAndTopologyEvidence() throws Exception {
        Path evidence = createEvidenceDirectory();
        Files.writeString(evidence.resolve("certification.properties"), """
                application=orders-api
                environment=staging
                application.commit=0123456789abcdef
                framework.version=0.10.0
                inventory.complete=true
                inventory.routeCount=2
                redis.failover=failed
                sql.failover=passed
                rollback.drill=passed
                canary.ready=passed
                redis.failoverEvidence=evidence/redis-failover.md
                sql.failoverEvidence=evidence/sql-failover.md
                rollback.drillEvidence=evidence/rollback.md
                canary.evidence=evidence/canary.md
                """);
        Files.delete(evidence.resolve("evidence/redis-failover.md"));

        ProductionCertificationValidator.Result result = new ProductionCertificationValidator()
                .validate(evidence, evidence.resolve("route-coverage.csv"));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("redis.failover must be passed")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("inventory.routeCount=2")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("redis.failoverEvidence")));
    }

    @Test
    void shouldRejectEvidenceFromAnotherCommit() throws Exception {
        Path evidence = createEvidenceDirectory();
        writeEvidence(evidence.resolve("evidence/parity.md"), "fedcba9876543210", "Parity matched.");

        ProductionCertificationValidator.Result result = new ProductionCertificationValidator()
                .validate(evidence, evidence.resolve("route-coverage.csv"));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("does not match application.commit")));
    }

    private Path createEvidenceDirectory() throws Exception {
        Path evidence = temporaryDirectory.resolve("cachedb-certification");
        Path reports = evidence.resolve("evidence");
        Files.createDirectories(reports);
        Files.writeString(evidence.resolve("certification.properties"), """
                application=orders-api
                environment=staging
                application.commit=0123456789abcdef
                framework.version=0.10.0
                inventory.complete=true
                inventory.routeCount=1
                redis.failover=passed
                sql.failover=passed
                rollback.drill=passed
                canary.ready=passed
                redis.failoverEvidence=evidence/redis-failover.md
                sql.failoverEvidence=evidence/sql-failover.md
                rollback.drillEvidence=evidence/rollback.md
                canary.evidence=evidence/canary.md
                """);
        Files.writeString(evidence.resolve("route-coverage.csv"), """
                RouteName,RouteKind,Owner,QueryShape,CacheDbShape,WarmStatus,WarmEvidence,CompareStatus,CompareEvidence,MemoryStatus,MemoryEvidence,CutoverStatus,RollbackPlan,RollbackEvidence,Blocker
                customer-orders,api,orders-team,"customer filter; date desc",projection,passed,evidence/warm.md,matched,evidence/parity.md,within budget,evidence/memory.md,ready,"route flag returns traffic to SQL",evidence/rollback.md,none
                """);
        writeEvidence(reports.resolve("redis-failover.md"), "0123456789abcdef", "Redis failover passed.");
        writeEvidence(reports.resolve("sql-failover.md"), "0123456789abcdef", "SQL reconnect passed.");
        writeEvidence(reports.resolve("rollback.md"), "0123456789abcdef", "Rollback drill passed.");
        writeEvidence(reports.resolve("canary.md"), "0123456789abcdef", "Canary checks passed.");
        writeEvidence(reports.resolve("warm.md"), "0123456789abcdef", "Warm coverage passed.");
        writeEvidence(reports.resolve("parity.md"), "0123456789abcdef", "Parity matched.");
        writeEvidence(reports.resolve("memory.md"), "0123456789abcdef", "Redis memory stayed within budget.");
        return evidence;
    }

    private void writeEvidence(Path path, String commit, String summary) throws Exception {
        Files.writeString(path, """
                status: passed
                commit: %s
                environment: staging
                owner: orders-team
                generated-at: 2026-08-13T12:00:00Z
                summary: %s
                """.formatted(commit, summary));
    }
}
