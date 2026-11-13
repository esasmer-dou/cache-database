package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.redis.RedisTenantQueueEraser;
import java.util.*;
import org.junit.jupiter.api.Test;

class PurgeQueueWorkCodecTest {
    @Test void originalPayloadAndRouteRoundTripWithoutTraceOrBusinessColumnCarriers(){
        String route=ProtectionKey.hash("queue");
        var entry=new RedisTenantQueueEraser.Entry("42-7",List.of("tenantGeneration","8","payload","ğİışöü"));
        var value=PurgeQueueWorkCodec.decode(PurgeQueueWorkCodec.encode(route,entry));
        assertEquals(route,value.route());assertEquals(entry,value.entry());
    }
    @Test void corruptTruncatedTrailingOrOversizedFramesFailClosed(){
        String route=ProtectionKey.hash("queue");
        byte[] frame=PurgeQueueWorkCodec.encode(route,new RedisTenantQueueEraser.Entry("1-0",List.of("k","v")));
        assertThrows(ProtectionException.class,()->PurgeQueueWorkCodec.decode(Arrays.copyOf(frame,frame.length-1)));
        assertThrows(ProtectionException.class,()->PurgeQueueWorkCodec.decode(Arrays.copyOf(frame,frame.length+1)));
        assertThrows(ProtectionException.class,()->PurgeQueueWorkCodec.decode(new byte[1048577]));
        byte[] corrupt=frame.clone();Arrays.fill(corrupt,0,4,(byte)0xff);
        assertThrows(ProtectionException.class,()->PurgeQueueWorkCodec.decode(corrupt));
    }
}
