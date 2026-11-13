package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedRedisWireTest {
    private static byte[] ascii(String value){return value.getBytes(StandardCharsets.US_ASCII);}
    @Test void oversizedDeclaredBulkAndArrayFailBeforeTheirPayloadIsAllocated() {
        for(String frame:new String[]{"$999999999\r\n","*999999999\r\n","$1025\r\n"}) {
            assertEquals(BoundedRedisWire.Code.INPUT_LIMIT,assertThrows(BoundedRedisWire.Failure.class,
                    ()->BoundedRedisWire.decode(ascii(frame),BoundedRedisWire.Limits.scan())).code());
        }
    }
    @Test void nestingMalformedUtf8TrailingBytesAndOversizedFramesFailClosed() {
        assertEquals(BoundedRedisWire.Code.INPUT_LIMIT,assertThrows(BoundedRedisWire.Failure.class,
                ()->BoundedRedisWire.decode(ascii("*1\r\n".repeat(8)+"+OK\r\n"),BoundedRedisWire.Limits.scan())).code());
        assertEquals(BoundedRedisWire.Code.PROTOCOL,assertThrows(BoundedRedisWire.Failure.class,
                ()->BoundedRedisWire.decode(new byte[]{'$','2','\r','\n',(byte)0xc3,0x28,'\r','\n'},BoundedRedisWire.Limits.scan())).code());
        assertThrows(BoundedRedisWire.Failure.class,()->BoundedRedisWire.decode(ascii("+OK\r\njunk"),BoundedRedisWire.Limits.scan()));
        assertThrows(BoundedRedisWire.Failure.class,()->BoundedRedisWire.decode(new byte[1048577],BoundedRedisWire.Limits.scan()));
    }
    @Test void fullOverChunkReplyIsNotTruncatedAndRemainsPersistable() {
        StringBuilder frame=new StringBuilder("*2\r\n$1\r\n0\r\n*129\r\n");
        for(int i=0;i<129;i++)frame.append("$1\r\nx\r\n");
        var reply=BoundedRedisWire.decode(ascii(frame.toString()),BoundedRedisWire.Limits.scan());
        var scanned=RedisPurgeScanner.decode(RedisPurgeScanner.Kind.OWNER,"17",reply,"a".repeat(64));
        assertEquals(129,scanned.items().size());assertEquals("0",scanned.nextCursor());
        assertArrayEquals(ascii(frame.toString()),scanned.wire());
        assertEquals(128,scanned.items().subList(0,128).size());assertEquals(1,scanned.items().subList(128,129).size());
    }
    @Test void explicitCloseIsIdempotentAndForbidsNewIo() {
        var wire=new BoundedRedisWire(new InetSocketAddress(InetAddress.getLoopbackAddress(),1),null,null,
                null,null,0,100,100,1);
        wire.close();wire.close();
        assertEquals(BoundedRedisWire.Code.IO_FAILURE,assertThrows(BoundedRedisWire.Failure.class,wire::connect).code());
    }
}
