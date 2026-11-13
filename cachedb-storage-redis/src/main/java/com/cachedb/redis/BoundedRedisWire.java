package com.reactor.cachedb.redis;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.net.ssl.*;

/** Purge/admin RESP2 transport. Hard input bounds are checked BEFORE bulk/array allocation. */
public final class BoundedRedisWire implements AutoCloseable {
    public enum Code { BUSY, INPUT_LIMIT, PROTOCOL, TOPOLOGY_CHANGED, IO_FAILURE }
    public static final class Failure extends RuntimeException {
        private final Code code;
        Failure(Code code) { super(code.name()); this.code=code; }
        public Code code() { return code; }
    }
    public record Limits(int frameBytes,int bulkBytes,int nodes,int commandBytes) {
        public Limits {
            if (frameBytes<1024 || frameBytes>1024*1024 || bulkBytes<20 || bulkBytes>262144
                    || bulkBytes>frameBytes || nodes<16 || nodes>8192 || commandBytes<1024 || commandBytes>8*1024*1024)
                throw new IllegalArgumentException("Invalid bounded RESP limits");
        }
        public static Limits scan() { return new Limits(1024*1024,1024,8192,16384); }
        public static Limits entries() { return new Limits(1024*1024,262144,8192,8*1024*1024); }
    }
    public record Reply(Object value,byte[] encoded) {
        public Reply { encoded=encoded.clone(); }
        @Override public byte[] encoded(){return encoded.clone();}
        @Override public String toString(){return "BoundedRedisReply[bytes="+encoded.length+"]";}
    }
    private final InetSocketAddress remote;
    private final String tlsName,user;
    private final char[] password;
    private final SSLSocketFactory tls;
    private final int database,connectMillis,readMillis;
    private final Semaphore permits;
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    private final Set<Socket> active=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledThreadPoolExecutor deadlines;


    public BoundedRedisWire(InetSocketAddress resolvedRemote,String tlsName,SSLSocketFactory tls,
            String user,char[] password,int database,int connectMillis,int readMillis,int maxConcurrent) {
        if (resolvedRemote==null || resolvedRemote.isUnresolved() || database<0 || database>1024
                || connectMillis<1 || connectMillis>10000 || readMillis<1 || readMillis>30000
                || maxConcurrent<1 || maxConcurrent>8 || user!=null && user.length()>256
                || password!=null && password.length>4096 || tls!=null && (tlsName==null || tlsName.isBlank()))
            throw new IllegalArgumentException("Invalid bounded Redis endpoint");
        remote=resolvedRemote; this.tlsName=tlsName; this.tls=tls; this.user=user;
        this.password=password==null?new char[0]:password.clone(); this.database=database;
        this.connectMillis=connectMillis; this.readMillis=readMillis; permits=new Semaphore(maxConcurrent,true);
        deadlines=new java.util.concurrent.ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon(true).name("cachedb-purge-io-deadline-",0).factory());
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        deadlines.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    public Session connect() {
        if(closed.get())throw new Failure(Code.IO_FAILURE);
        if(!permits.tryAcquire())throw new Failure(Code.BUSY);
        Socket raw=null,socket=null;
        java.util.concurrent.ScheduledFuture<?> deadline=null;
        try {
            raw=new Socket();raw.setTcpNoDelay(true);socket=raw;active.add(raw);
            if(closed.get())throw new Failure(Code.IO_FAILURE);
            deadline=deadline(raw,connectMillis+readMillis);
            raw.connect(remote,connectMillis);raw.setSoTimeout(readMillis);
            if(tls!=null) {
                var secure=(SSLSocket)tls.createSocket(raw,tlsName,remote.getPort(),true);
                var parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
                secure.setSSLParameters(parameters);secure.setSoTimeout(readMillis);socket=secure;secure.startHandshake();
            }
            Session session=new Session(socket,raw);
            if(password.length>0) {
                if(user==null || user.isEmpty())session.call(Limits.entries(),"AUTH",new String(password));
                else session.call(Limits.entries(),"AUTH",user,new String(password));
            }
            if(database!=0)session.call(Limits.scan(),"SELECT",Integer.toString(database));
            return session;
        } catch(IOException | RuntimeException failure) {
            if(raw!=null){closeSocket(raw);active.remove(raw);}
            if(socket!=null)closeSocket(socket);
            permits.release();
            throw failure instanceof Failure safe?safe:new Failure(Code.IO_FAILURE);
        } finally {if(deadline!=null)deadline.cancel(false);}
    }
    private java.util.concurrent.ScheduledFuture<?> deadline(Socket socket,int millis) {
        try {
            // At most two live timers per admitted connection (connect plus its AUTH/SELECT call).
            return deadlines.schedule(()->closeSocket(socket),millis,java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch(java.util.concurrent.RejectedExecutionException unavailable){throw new Failure(Code.IO_FAILURE);}
    }
    private static void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}}
    /** Closes owned sockets/timer only; admission permits remain held until their owner exits physical I/O. */
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;
        for(Socket socket:active)closeSocket(socket);
        deadlines.shutdownNow();
        Arrays.fill(password,'\0');
    }
    public final class Session implements AutoCloseable {
        private final Socket socket,physical;
        private final InputStream input;
        private final OutputStream output;
        private final Thread owner=Thread.currentThread();
        private boolean closed;
        private Session(Socket socket,Socket physical) throws IOException {
            this.socket=socket;this.physical=physical;
            input=new InputBuffer(socket.getInputStream());
            output=new OutputBuffer(socket.getOutputStream());
        }
        public Reply call(Limits limits,String... args) {
            if (Thread.currentThread()!=owner || closed) throw new Failure(Code.PROTOCOL);
            if (args.length<1 || args.length>8192) throw new Failure(Code.INPUT_LIMIT);
            var encoded=new ArrayList<byte[]>(args.length); long bytes=32;
            var deadline=deadline(physical,readMillis);
            try {
                for (String arg:args) {
                    if (arg==null || arg.length()>limits.commandBytes()) throw new Failure(Code.INPUT_LIMIT);
                    var buffer=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(arg));
                    byte[] value=new byte[buffer.remaining()]; buffer.get(value);
                    bytes+=value.length+32L;
                    if (bytes>limits.commandBytes()) throw new Failure(Code.INPUT_LIMIT);
                    encoded.add(value);
                }
                ascii("*"+args.length+"\r\n");
                for (byte[] value:encoded) { ascii("$"+value.length+"\r\n"); output.write(value); ascii("\r\n"); }
                output.flush();
                return new Decoder(input,limits).read();
            } catch (IOException failure) { throw new Failure(Code.IO_FAILURE); }
            finally { deadline.cancel(false); }
        }
        private void ascii(String value) throws IOException { output.write(value.getBytes(StandardCharsets.US_ASCII)); }
        @Override public void close() {
            if (Thread.currentThread()!=owner) throw new Failure(Code.PROTOCOL);
            if (closed) return; closed=true;
            try { closeSocket(physical); closeSocket(socket); }
            finally { active.remove(physical); permits.release(); }
        }
    }
    /** No synchronized stream wrapper may hold a monitor across socket I/O on Java 21. */
    private static final class InputBuffer extends InputStream {
        private final InputStream input; private final byte[] buffer=new byte[4096]; private int index,end;
        InputBuffer(InputStream input){this.input=input;}
        public int read() throws IOException {
            if (index==end) { end=input.read(buffer); index=0; if(end<0)return -1; if(end==0)throw new IOException("Empty socket read"); }
            return buffer[index++]&255;
        }
    }
    private static final class OutputBuffer extends OutputStream {
        private final OutputStream output; private final byte[] buffer=new byte[4096]; private int size;
        OutputBuffer(OutputStream output){this.output=output;}
        public void write(int value) throws IOException { if(size==buffer.length)flush();buffer[size++]=(byte)value; }
        public void write(byte[] values,int start,int length) throws IOException {
            while(length>0){int n=Math.min(length,buffer.length-size);System.arraycopy(values,start,buffer,size,n);
                size+=n;start+=n;length-=n;if(size==buffer.length)flush();}
        }
        public void flush() throws IOException { if(size>0){output.write(buffer,0,size);size=0;}output.flush(); }
    }
    /** Also decodes a durable pending frame without sockets or unbounded object deserialization. */
    public static Reply decode(byte[] encoded,Limits limits) {
        if (encoded==null || encoded.length>limits.frameBytes()) throw new Failure(Code.INPUT_LIMIT);
        var input=new ByteArrayInputStream(encoded);
        Reply reply=new Decoder(input,limits).read();
        if (input.available()!=0) throw new Failure(Code.PROTOCOL);
        return reply;
    }
    private static final class Decoder {
        private final InputStream input;
        private final Limits limits;
        private final ByteArrayOutputStream capture=new ByteArrayOutputStream(4096);
        private int nodes;
        Decoder(InputStream input,Limits limits){this.input=input;this.limits=limits;}
        Reply read() {
            try { return new Reply(value(0),capture.toByteArray()); }
            catch (IOException failure) { throw new Failure(Code.IO_FAILURE); }
        }
        private int next() throws IOException {
            if (capture.size()>=limits.frameBytes()) throw new Failure(Code.INPUT_LIMIT);
            int b=input.read(); if (b<0) throw new EOFException(); capture.write(b); return b;
        }
        private String line() throws IOException {
            byte[] bytes=new byte[128]; int n=0;
            while (true) {
                int b=next();
                if (b=='\r') { if (next()!='\n') throw new Failure(Code.PROTOCOL); break; }
                if (n==bytes.length) throw new Failure(Code.INPUT_LIMIT); bytes[n++]=(byte)b;
            }
            return new String(bytes,0,n,StandardCharsets.US_ASCII);
        }
        private long integer() throws IOException {
            String value=line();
            if (!value.matches("-?[0-9]{1,19}")) throw new Failure(Code.PROTOCOL);
            try { return Long.parseLong(value); } catch(NumberFormatException bad){throw new Failure(Code.PROTOCOL);}
        }
        private Object value(int depth) throws IOException {
            if (depth>6 || ++nodes>limits.nodes()) throw new Failure(Code.INPUT_LIMIT);
            int marker=next();
            if (marker==':') return integer();
            if (marker=='+') return line();
            if (marker=='-') {
                String error=line();
                throw new Failure(error.startsWith("MOVED ") || error.startsWith("ASK ") || error.startsWith("READONLY")
                        ?Code.TOPOLOGY_CHANGED:Code.PROTOCOL);
            }
            if (marker=='$') {
                long length=integer(); if (length==-1) return null;
                if (length<0 || length>limits.bulkBytes() || length+2L>limits.frameBytes()-capture.size())
                    throw new Failure(Code.INPUT_LIMIT);
                byte[] bytes=new byte[(int)length];
                for (int i=0;i<bytes.length;i++) bytes[i]=(byte)next();
                if (next()!='\r' || next()!='\n') throw new Failure(Code.PROTOCOL);
                try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
                catch(CharacterCodingException invalid){throw new Failure(Code.PROTOCOL);}
            }
            if (marker=='*') {
                long count=integer(); if (count==-1) return null;
                if (count<0 || count>limits.nodes()-nodes) throw new Failure(Code.INPUT_LIMIT);
                var values=new ArrayList<Object>((int)count);
                for (int i=0;i<count;i++) values.add(value(depth+1));
                return Collections.unmodifiableList(values);
            }
            throw new Failure(Code.PROTOCOL);
        }
    }
}
