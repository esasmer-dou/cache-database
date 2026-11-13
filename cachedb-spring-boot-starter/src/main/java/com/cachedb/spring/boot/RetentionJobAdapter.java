package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.codec.CacheValueCodec;
import com.reactor.cachedb.core.retention.RetentionTarget;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.starter.RetentionSweeper;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional registration; disabled by default, no scheduler/executor/resource ownership. */
public final class RetentionJobAdapter implements CacheDistributedJobHandler.Typed<RetentionJobAdapter.Job> {
    public static final String ROUTE="cachedb.retention.v1";
    public record Job(String scopeToken,int maxRows) {
        public Job {
            if (scopeToken==null || !scopeToken.matches("[0-9a-f]{64}") || maxRows<1 || maxRows>10000)
                throw new IllegalArgumentException("Invalid retention job");
        }
    }
    private final boolean enabled;
    private final RetentionSweeper sweeper;
    private final Map<String,Class<?>> targets;
    private final List<String> scopes;
    private final AtomicInteger cursor=new AtomicInteger();
    public RetentionJobAdapter(RetentionSweeper sweeper,Collection<? extends RetentionTarget> targets) {
        this(sweeper,targets,false);
    }
    public RetentionJobAdapter(RetentionSweeper sweeper,Collection<? extends RetentionTarget> targets,boolean enabled) {
        if (targets.size()>1024) throw new IllegalArgumentException("At most 1024 retention jobs");
        this.sweeper=Objects.requireNonNull(sweeper); this.enabled=enabled;
        var map=new LinkedHashMap<String,Class<?>>();
        for (var target:targets) if (map.put(target.scopeToken(),target.entityType())!=null)
            throw new IllegalArgumentException("Duplicate retention scope");
        this.targets=Map.copyOf(map); scopes=List.copyOf(map.keySet());
    }
    @Override public CacheDistributedJobDefinition<Job> definition() { return CacheDistributedJobDefinition.of(ROUTE,Job.class); }
    @Override public CacheValueCodec<Job> argumentCodec() {
        return new CacheValueCodec<>() {
            public String encodeValue(Job value) { return "1|"+value.scopeToken()+"|"+value.maxRows(); }
            public Job decodeValue(String value) throws IOException {
                if (value==null || value.length()>80) throw new IOException("Invalid retention job");
                String[] fields=value.split("\\|",-1);
                if (fields.length!=3 || !fields[0].equals("1")) throw new IOException("Invalid retention job");
                try { return new Job(fields[1],Integer.parseInt(fields[2])); }
                catch (RuntimeException invalid) { throw new IOException("Invalid retention job"); }
            }
        };
    }
    @Override public Object execute(Job job,CacheDistributedJobContext context) {
        if (!enabled) throw new IllegalStateException("Retention scheduling is disabled");
        Class<?> type=targets.get(job.scopeToken());
        if (type==null) throw new IllegalArgumentException("Unknown retention scope");
        try (var admin=CacheTenantContext.administrative()) {
            var result=sweeper.sweepOnce(type,job.maxRows());
            var counts=Map.of("examined",result.examined(),"evicted",result.evicted(),"indexCleaned",result.indexCleaned(),
                    "deleteAccepted",result.deleteAccepted(),"skipped",result.skipped());
            context.checkpoint(counts); return counts;
        }
    }
    public List<CacheDistributedJobSnapshot> enqueueTick(CacheDistributedJobExecutor jobs,int maxJobs,int maxRows) {
        if (maxJobs<1 || maxJobs>16 || maxRows<1 || maxRows>10000) throw new IllegalArgumentException("Invalid retention tick");
        if (!enabled || scopes.isEmpty()) return List.of();
        var result=new ArrayList<CacheDistributedJobSnapshot>();
        for (int i=0;i<Math.min(maxJobs,scopes.size());i++) {
            int at=Math.floorMod(cursor.getAndIncrement(),scopes.size());
            result.add(jobs.submit(ROUTE,new Job(scopes.get(at),maxRows)));
        }
        return List.copyOf(result);
    }
}
