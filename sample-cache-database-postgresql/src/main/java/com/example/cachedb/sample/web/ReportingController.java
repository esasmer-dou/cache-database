package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.AuditEventEntity;
import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.ReportJobEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportingController {

    private static final String AUDIT_ARCHIVE_SQL = """
            SELECT audit_event_id, entity_name, entity_id, event_type, severity, actor, created_at, message
            FROM sample_audit_events
            WHERE entity_name = ? AND entity_id = ?
            ORDER BY created_at DESC, audit_event_id DESC
            OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
            """;

    private final GeneratedCacheModule.Scope domain;
    private final JdbcTemplate jdbcTemplate;

    public ReportingController(
            GeneratedCacheModule.Scope domain,
            JdbcTemplate jdbcTemplate
    ) {
        this.domain = domain;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/jobs/live")
    public List<ReportJobEntity> liveJobs(@RequestParam(defaultValue = "25") int limit) {
        return domain.reportJobs().queries()
                .liveReportJobs(ApiLimits.requireInRange("limit", limit, 1, 50));
    }

    @GetMapping("/jobs/type/{reportType}")
    public List<ReportJobEntity> jobsByType(
            @PathVariable String reportType,
            @RequestParam(defaultValue = "25") int limit
    ) {
        return domain.reportJobs().queries()
                .reportJobsByType(reportType, ApiLimits.requireInRange("limit", limit, 1, 50));
    }

    @PostMapping("/jobs")
    public ResponseEntity<WriteAccepted<ReportJobEntity>> createJob(
            @Valid @RequestBody CreateReportJobRequest request
    ) {
        long now = Instant.now().getEpochSecond();
        ReportJobEntity job = new ReportJobEntity();
        job.reportJobId = request.reportJobId();
        job.reportType = request.reportType() == null ? "ORDER_SUMMARY" : request.reportType();
        job.status = request.status() == null ? "QUEUED" : request.status();
        job.requestedBy = request.requestedBy() == null ? "sample-user" : request.requestedBy();
        job.createdAt = now;
        job.updatedAt = now;
        job.rowCount = 0;
        job.failureReason = null;
        ReportJobEntity saved = domain.reportJobs().save(job);
        return ResponseEntity.accepted().body(WriteAccepted.of("CREATE", "ReportJobEntity", saved.reportJobId, saved));
    }

    @PatchMapping("/jobs/{reportJobId}/status")
    public ResponseEntity<WriteAccepted<ReportJobEntity>> updateJobStatus(
            @PathVariable long reportJobId,
            @Valid @RequestBody UpdateReportJobStatusRequest request
    ) {
        ReportJobEntity job = domain.reportJobs().findById(reportJobId)
                .orElseThrow(() -> new ResourceNotFoundException("Report job not found in active set: " + reportJobId));
        job.status = request.status();
        job.rowCount = request.rowCount() == null ? job.rowCount : request.rowCount();
        job.failureReason = request.failureReason();
        job.updatedAt = Instant.now().getEpochSecond();
        ReportJobEntity saved = domain.reportJobs().save(job);
        return ResponseEntity.accepted().body(WriteAccepted.of("UPDATE", "ReportJobEntity", saved.reportJobId, saved));
    }

    @GetMapping("/audit/security")
    public List<AuditEventEntity> securityAuditEvents(@RequestParam(defaultValue = "25") int limit) {
        return domain.auditEvents().queries()
                .securityAuditEvents(ApiLimits.requireInRange("limit", limit, 1, 50));
    }

    @GetMapping("/audit/archive")
    public List<AuditEventEntity> auditArchive(
            @RequestParam String entityName,
            @RequestParam long entityId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return jdbcTemplate.query(
                AUDIT_ARCHIVE_SQL,
                (resultSet, rowNumber) -> {
                    AuditEventEntity event = new AuditEventEntity();
                    event.auditEventId = resultSet.getLong("audit_event_id");
                    event.entityName = resultSet.getString("entity_name");
                    event.entityId = resultSet.getLong("entity_id");
                    event.eventType = resultSet.getString("event_type");
                    event.severity = resultSet.getString("severity");
                    event.actor = resultSet.getString("actor");
                    event.createdAt = resultSet.getLong("created_at");
                    event.message = resultSet.getString("message");
                    return event;
                },
                entityName,
                entityId,
                ApiLimits.requireInRange("limit", limit, 1, 500)
        );
    }

    public record CreateReportJobRequest(
            @NotNull @Positive Long reportJobId,
            @NotBlank @Size(max = 64) String reportType,
            @Size(max = 32) String status,
            @NotBlank @Size(max = 128) String requestedBy
    ) {
    }

    public record UpdateReportJobStatusRequest(
            @NotBlank @Size(max = 32) String status,
            @PositiveOrZero Integer rowCount,
            @Size(max = 2_000) String failureReason
    ) {
    }
}
