package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.starter.RetentionSweeper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetentionJobAdapterTest {
    @Test void schedulingDefaultsDisabledAndCodecIsBoundedAndExplicit() throws Exception {
        var adapter=new RetentionJobAdapter(new RetentionSweeper(List.of()),List.of());
        assertTrue(adapter.enqueueTick(null,16,128).isEmpty());
        var codec=adapter.argumentCodec();
        var job=new RetentionJobAdapter.Job("a".repeat(64),128);
        assertEquals(job,codec.decodeValue(codec.encodeValue(job)));
        assertThrows(IOException.class,()->codec.decodeValue("x".repeat(81)));
        assertThrows(IllegalStateException.class,()->adapter.execute(job,null));
    }
}
