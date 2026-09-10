package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "sample_report_jobs", redisNamespace = "sample-report-jobs")
public class ReportJobEntity {

    @CacheId(column = "report_job_id")
    public Long reportJobId;

    @CacheColumn("report_type")
    public String reportType;

    @CacheColumn("status")
    public String status;

    @CacheColumn("requested_by")
    public String requestedBy;

    @CacheColumn("created_at")
    public Long createdAt;

    @CacheColumn("updated_at")
    public Long updatedAt;

    @CacheColumn("row_count")
    public Integer rowCount;

    @CacheColumn("failure_reason")
    public String failureReason;

    public ReportJobEntity() {
    }
}
