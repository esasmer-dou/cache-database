package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.reporting.ReportingApplicationService;
import com.example.cachedb.sample.domain.AuditEventEntity;
import com.example.cachedb.sample.domain.ReportJobEntity;
import com.reactor.cachedb.core.repository.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
@Validated
public class ReportingController {

    private final ReportingApplicationService reporting;

    public ReportingController(ReportingApplicationService reporting) {
        this.reporting = reporting;
    }

    @GetMapping("/jobs/live")
    public List<ReportJobEntity> liveJobs(@RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit) {
        return reporting.liveJobs(limit);
    }

    @GetMapping("/jobs/type/{reportType}")
    public CursorPage<ReportJobEntity> jobsByType(
            @PathVariable @NotBlank @Size(max = 64) String reportType,
            @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
            @RequestParam(required = false) @Size(max = 8_192) String after
    ) {
        return reporting.jobsByType(reportType, limit, after);
    }

    @PostMapping("/jobs")
    public ResponseEntity<WriteAccepted<ReportJobEntity>> createJob(
            @Valid @RequestBody CreateReportJobRequest request
    ) {
        var receipt = reporting.createJob(new ReportingApplicationService.CreateReportJob(
                request.reportJobId(), request.reportType(), request.status(), request.requestedBy()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("CREATE", "ReportJobEntity", receipt));
    }

    @PatchMapping("/jobs/{reportJobId}/status")
    public ResponseEntity<WriteAccepted<ReportJobEntity>> updateJobStatus(
            @PathVariable long reportJobId,
            @Valid @RequestBody UpdateReportJobStatusRequest request
    ) {
        var receipt = reporting.updateJobStatus(reportJobId, new ReportingApplicationService.UpdateReportJobStatus(
                request.status(), request.rowCount(), request.failureReason()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("UPDATE", "ReportJobEntity", receipt));
    }

    @GetMapping("/audit/security")
    public List<AuditEventEntity> securityAuditEvents(
            @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit
    ) {
        return reporting.securityAuditEvents(limit);
    }

    @GetMapping("/audit/archive")
    public CursorPage<AuditEventEntity> auditArchive(
            @RequestParam @NotBlank @Size(max = 128) String entityName,
            @RequestParam @Positive long entityId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) @Size(max = 8_192) String after
    ) {
        return reporting.auditArchive(entityName, entityId, limit, after);
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
