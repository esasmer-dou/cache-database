package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.redis.RedisTenantQueueEraser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Temporary durable queue payload. No record component, business-column or trace-metadata carrier. */
final class PurgeQueueWorkCodec {
    record Value(String route,RedisTenantQueueEraser.Entry entry){}
    static byte[] encode(String route,RedisTenantQueueEraser.Entry entry){
        ProtectionKey.token(route);
        try{
            long size=12L+route.length()+entry.id().length();
            for(String field:entry.fields())size=Math.addExact(size,4L+com.reactor.cachedb.core.codec.Utf8Length.of(field));
            if(size>1048576)throw incomplete();
            var buffer=new ByteArrayOutputStream((int)size);
            var out=new DataOutputStream(buffer);
            write(out,route);write(out,entry.id());out.writeInt(entry.fields().size());
            for(String field:entry.fields()){write(out,field);if(buffer.size()>1048576)throw incomplete();}
            out.flush();if(buffer.size()>1048576)throw incomplete();return buffer.toByteArray();
        }catch(IOException impossible){throw incomplete();}
    }
    static Value decode(byte[] bytes){
        if(bytes.length>1048576)throw incomplete();
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            String route=read(in,64);ProtectionKey.token(route);String id=read(in,41);
            int size=in.readInt();if(size<2||size>8192||size%2!=0)throw incomplete();
            var fields=new ArrayList<String>(size);
            for(int i=0;i<size;i++)fields.add(read(in,262144));
            if(in.available()!=0)throw incomplete();
            return new Value(route,new RedisTenantQueueEraser.Entry(id,fields));
        }catch(IOException invalid){throw incomplete();}
    }
    private static void write(DataOutputStream out,String value)throws IOException{
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>262144)throw incomplete();out.writeInt(bytes.length);out.write(bytes);
    }
    private static String read(DataInputStream in,int max)throws IOException{
        int size=in.readInt();if(size<0||size>max||size>in.available())throw incomplete();
        byte[] bytes=in.readNBytes(size);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw incomplete();}
    }
    private static ProtectionException incomplete(){return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);}
}
