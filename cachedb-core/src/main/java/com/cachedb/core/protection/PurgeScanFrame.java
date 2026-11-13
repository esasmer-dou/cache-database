package com.reactor.cachedb.core.protection;

public record PurgeScanFrame(String inputCursor,String nextCursor,boolean finished,int items,
        String topology,byte[] encoded) {
    public PurgeScanFrame {
        if(inputCursor==null || nextCursor==null || inputCursor.length()>64 || nextCursor.length()>64
                || items<0 || items>4096 || encoded==null || encoded.length>1024*1024)
            throw new IllegalArgumentException("Invalid bounded purge frame");
        ProtectionKey.token(topology);encoded=encoded.clone();
    }
    @Override public byte[] encoded(){return encoded.clone();}
    @Override public String toString(){return "PurgeScanFrame[items="+items+"]";}
}
