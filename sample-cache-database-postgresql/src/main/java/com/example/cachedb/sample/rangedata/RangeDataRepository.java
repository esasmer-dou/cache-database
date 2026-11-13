package com.example.cachedb.sample.rangedata;

import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.spring.data.CacheDbSpringDataRepository;
import java.util.List;
import org.springframework.data.domain.*;

@CacheRepository(entity = RangeDataRow.class, springBean = false)
public interface RangeDataRepository extends CacheDbSpringDataRepository<RangeDataRow, String> {
    Page<RangeDataRow> findByRunIdAndStatus(String runId, String status, Pageable pageable);
    Slice<RangeDataRow> readByRunIdAndStatus(String runId, String status, Pageable pageable);
    Window<RangeDataRow> readByRunIdAndStatusOrderByRankValueAscRowIdAsc(
            String runId, String status, ScrollPosition position, Limit limit);
    long countByRunIdAndStatus(String runId, String status);
    boolean existsByRunIdAndStatus(String runId, String status);
    List<RangeDataRow> deleteByRunIdAndStatus(String runId, String status);
}
