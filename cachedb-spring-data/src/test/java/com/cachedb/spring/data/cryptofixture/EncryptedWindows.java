package com.reactor.cachedb.spring.data.cryptofixture;

import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.spring.data.CacheDbSpringDataRepository;
import org.springframework.data.domain.*;

@CacheRepository(entity=EncryptedWindowRow.class,springBean=false)
public interface EncryptedWindows extends CacheDbSpringDataRepository<EncryptedWindowRow,Long> {
    Window<EncryptedWindowRow> findFirst2ByRankingOrderByIdAsc(int ranking,ScrollPosition position);
}
