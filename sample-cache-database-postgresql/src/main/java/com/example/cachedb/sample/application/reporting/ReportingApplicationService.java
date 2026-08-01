package com.example.cachedb.sample.application.reporting;

import com.example.cachedb.sample.application.SampleEntityNotFoundException;
import com.example.cachedb.sample.domain.AuditEventEntity;
import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.ReportJobEntity;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.page.VersionedEntity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class ReportingApplicationService {

    private final GeneratedCacheModule.Scope domain;
    private final Clock clock;

    public ReportingApplicationService(GeneratedCacheModule.Scope domain, Clock clock) {
        this.domain = domain;
        this.clock = clock;
    }

    public List<ReportJobEntity> liveJobs(int limit) {
        return domain.reportJobs().queries().liveReportJobs(limit);
    }

    public List<ReportJobEntity> jobsByType(String reportType, int limit) {
        return domain.reportJobs().queries().reportJobsByType(reportType, limit);
    }

    public WriteReceipt<ReportJobEntity, Long> createJob(CreateReportJob command) {
        long now = Instant.now(clock).getEpochSecond();
        ReportJobEntity entity = new ReportJobEntity();
        entity.reportJobId = command.reportJobId();
        entity.reportType = defaultText(command.reportType(), "ORDER_SUMMARY");
        entity.status = defaultText(command.status(), "QUEUED");
        entity.requestedBy = defaultText(command.requestedBy(), "sample-user");
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.rowCount = 0;
        return domain.reportJobs().saveWithReceipt(entity);
    }

    public WriteReceipt<ReportJobEntity, Long> updateJobStatus(long reportJobId, UpdateReportJobStatus command) {
        VersionedEntity<ReportJobEntity> current = domain.reportJobs().findVersionedById(reportJobId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Report job", reportJobId));
        ReportJobEntity entity = current.entity();
        entity.status = command.status();
        entity.rowCount = command.rowCount() == null ? entity.rowCount : command.rowCount();
        entity.failureReason = command.failureReason();
        entity.updatedAt = Instant.now(clock).getEpochSecond();
        return domain.reportJobs().save(entity, current.version());
    }

    public List<AuditEventEntity> securityAuditEvents(int limit) {
        return domain.auditEvents().queries().securityAuditEvents(limit);
    }

    public List<AuditEventEntity> auditArchive(String entityName, long entityId, int limit) {
        return domain.auditEvents().queries().eventsForEntitySource(entityName, entityId, limit);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record CreateReportJob(
            Long reportJobId,
            String reportType,
            String status,
            String requestedBy
    ) {
    }

    public record UpdateReportJobStatus(String status, Integer rowCount, String failureReason) {
    }
}
