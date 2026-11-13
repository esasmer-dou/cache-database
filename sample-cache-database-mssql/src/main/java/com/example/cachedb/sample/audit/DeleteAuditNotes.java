package com.example.cachedb.sample.audit;

import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.spring.data.CacheDbSpringDataRepository;
import java.util.List;

@CacheRepository(entity = DeleteAuditNote.class, springBean = false)
public interface DeleteAuditNotes extends CacheDbSpringDataRepository<DeleteAuditNote, String> {
    List<DeleteAuditNote> deleteByState(String state);
}
