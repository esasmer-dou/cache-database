package com.example.cachedb.sample.application.reporting;

import com.example.cachedb.sample.domain.AuditEventEntity;
import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.repository.AuditEventRepository;
import com.example.cachedb.sample.repository.ReportJobRepository;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.WindowRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class ReportingApplicationService {

    private final ReportJobRepository reportJobs;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ReportingApplicationService(
            ReportJobRepository reportJobs,
            AuditEventRepository auditEvents,
            Clock clock
    ) {
        this.reportJobs = reportJobs;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    public List<ReportJobEntity> liveJobs(int limit) {
        return reportJobs.live(limit).completeItems();
    }

    public List<ReportJobEntity> jobsByType(String reportType, int limit) {
        return reportJobs.byType(reportType, WindowRequest.first(limit)).completeItems();
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
        return reportJobs.save(entity);
    }

    public WriteReceipt<ReportJobEntity, Long> updateJobStatus(long reportJobId, UpdateReportJobStatus command) {
        return reportJobs.updateHot(reportJobId, entity -> {
            entity.status = command.status();
            entity.rowCount = command.rowCount() == null ? entity.rowCount : command.rowCount();
            entity.failureReason = command.failureReason();
            entity.updatedAt = Instant.now(clock).getEpochSecond();
            return entity;
        });
    }

    public List<AuditEventEntity> securityAuditEvents(int limit) {
        return auditEvents.security(limit).completeItems();
    }

    public List<AuditEventEntity> auditArchive(String entityName, long entityId, int limit) {
        return auditEvents.archive(entityName, entityId, WindowRequest.first(limit)).items();
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
