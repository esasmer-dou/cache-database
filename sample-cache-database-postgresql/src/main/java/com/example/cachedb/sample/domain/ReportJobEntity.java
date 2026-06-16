package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;

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

    @CacheNamedQuery("liveReportJobs")
    public static QuerySpec liveReportJobsQuery(int limit) {
        return QuerySpec.where(QueryFilter.in("status", List.<Object>of("QUEUED", "RUNNING", "FAILED")))
                .orderBy(QuerySort.desc("updated_at"), QuerySort.desc("report_job_id"))
                .limitTo(limit);
    }

    @CacheNamedQuery("reportJobsByType")
    public static QuerySpec reportJobsByTypeQuery(String reportType, int limit) {
        return QuerySpec.where(QueryFilter.eq("report_type", reportType))
                .orderBy(QuerySort.desc("created_at"), QuerySort.desc("report_job_id"))
                .limitTo(limit);
    }
}
