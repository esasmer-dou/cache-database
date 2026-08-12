package com.example.cachedb.sample.application.reporting;

import com.example.cachedb.sample.domain.AuditEventEntity;
import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.repository.AuditEventRepository;
import com.example.cachedb.sample.repository.ReportJobRepository;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.CursorPage;
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

    public CursorPage<ReportJobEntity> jobsByType(String reportType, int limit, String after) {
        return reportJobs.byType(reportType, WindowRequest.of(limit, after)).completePage();
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

    public CursorPage<AuditEventEntity> auditArchive(String entityName, long entityId, int limit, String after) {
        return auditEvents.archive(entityName, entityId, WindowRequest.of(limit, after)).page();
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
