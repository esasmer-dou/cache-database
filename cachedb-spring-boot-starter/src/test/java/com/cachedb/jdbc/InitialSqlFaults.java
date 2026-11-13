package com.reactor.cachedb.jdbc;

import java.io.PrintWriter;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;

/** Test-only explicit decorator: real provider commit happens BEFORE an injected lost-reply exception. No reflection. */
final class InitialSqlFaults implements DataSource {
    record Fault(String prefix, boolean afterCommit) {}
    private final DataSource delegate;
    private final JdbcTenantPurgeDialect dialect;
    private final java.util.ArrayList<String> templates = new java.util.ArrayList<>();
    private final ReentrantLock capture=new ReentrantLock();
    private boolean templateOverflow;
    java.util.List<String> templates() {
        capture.lock();try {
            if (templateOverflow) throw new AssertionError("SQL template capture exceeded its fixed budget");
            return java.util.List.copyOf(templates);
        }finally{capture.unlock();}
    }
    record Pause(String prefix,CountDownLatch entered,CountDownLatch release,AtomicInteger backend) {}
    final AtomicReference<Pause> paused=new AtomicReference<>();
    final AtomicReference<InitialSqlBackendProbe.Session> pausedBackend=new AtomicReference<>();
    final AtomicReference<Fault> armed = new AtomicReference<>();
    final AtomicInteger fired = new AtomicInteger();
    InitialSqlFaults(DataSource delegate) { this(delegate,null); }
    InitialSqlFaults(DataSource delegate,JdbcTenantPurgeDialect dialect) { this.delegate=delegate;this.dialect=dialect; }
    void arm(String sqlPrefix, boolean afterCommit) {
        if (!armed.compareAndSet(null,new Fault(sqlPrefix,afterCommit))) throw new IllegalStateException("Fault already armed");
    }
    void requireConsumed() { if (armed.get()!=null) throw new AssertionError("Commit fault boundary not exercised"); }
    public Connection getConnection() throws SQLException { return new Controlled(delegate.getConnection()); }
    public Connection getConnection(String user,String password) throws SQLException { return new Controlled(delegate.getConnection(user,password)); }
    public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
    public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }

    private final class Controlled implements Connection {
        private final Connection c;
        private Fault matched;
        Controlled(Connection c) { this.c=c; }
        public PreparedStatement prepareStatement(String sql) throws SQLException {
            capture.lock();try{if (templates.size()<1024) templates.add(sql); else templateOverflow=true;}finally{capture.unlock();}
            var q=c.prepareStatement(sql);var fault=armed.get();
            if (fault!=null&&sql.startsWith(fault.prefix())) matched=fault;
            var pause=paused.get();
            if(pause!=null&&sql.startsWith(pause.prefix())&&paused.compareAndSet(pause,null)) {
                try {
                    if(dialect==null)throw new SQLException("Explicit provider required for a physical SQL pause","CDBTP");
                    var session=InitialSqlBackendProbe.capture(c,dialect);
                    if(!pausedBackend.compareAndSet(null,session))throw new SQLException("Owned SQL pause already captured","CDBTP");
                    pause.backend().set(session.id());
                    pause.entered().countDown();
                    try{if(!pause.release().await(5,TimeUnit.SECONDS))throw new SQLException("Bounded test pause expired","CDBTP");}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException("Bounded test pause interrupted","CDBTP");}
                }catch(SQLException | RuntimeException | Error failure) {
                    try{q.close();}catch(SQLException ignored){}throw failure;
                }
            }
            return q;
        }
        public void commit() throws SQLException {
            boolean fail=matched!=null&&armed.compareAndSet(matched,null);
            if(fail&&!matched.afterCommit()) { fired.incrementAndGet(); throw lostReply(); }
            c.commit();
            if(fail) { fired.incrementAndGet(); throw lostReply(); }
        }
        public Statement createStatement() throws SQLException { return c.createStatement(); }
        public CallableStatement prepareCall(String sql) throws SQLException { return c.prepareCall(sql); }
        public String nativeSQL(String sql) throws SQLException { return c.nativeSQL(sql); }
        public void setAutoCommit(boolean value) throws SQLException { c.setAutoCommit(value); }
        public boolean getAutoCommit() throws SQLException { return c.getAutoCommit(); }
        public void rollback() throws SQLException { c.rollback(); }
        public void close() throws SQLException { c.close(); }
        public boolean isClosed() throws SQLException { return c.isClosed(); }
        public DatabaseMetaData getMetaData() throws SQLException { return c.getMetaData(); }
        public void setReadOnly(boolean value) throws SQLException { c.setReadOnly(value); }
        public boolean isReadOnly() throws SQLException { return c.isReadOnly(); }
        public void setCatalog(String value) throws SQLException { c.setCatalog(value); }
        public String getCatalog() throws SQLException { return c.getCatalog(); }
        public void setTransactionIsolation(int value) throws SQLException { c.setTransactionIsolation(value); }
        public int getTransactionIsolation() throws SQLException { return c.getTransactionIsolation(); }
        public SQLWarning getWarnings() throws SQLException { return c.getWarnings(); }
        public void clearWarnings() throws SQLException { c.clearWarnings(); }
        public Statement createStatement(int type,int concurrency) throws SQLException { return c.createStatement(type,concurrency); }
        public PreparedStatement prepareStatement(String sql,int type,int concurrency) throws SQLException { return c.prepareStatement(sql,type,concurrency); }
        public CallableStatement prepareCall(String sql,int type,int concurrency) throws SQLException { return c.prepareCall(sql,type,concurrency); }
        public Map<String,Class<?>> getTypeMap() throws SQLException { return c.getTypeMap(); }
        public void setTypeMap(Map<String,Class<?>> value) throws SQLException { c.setTypeMap(value); }
        public void setHoldability(int value) throws SQLException { c.setHoldability(value); }
        public int getHoldability() throws SQLException { return c.getHoldability(); }
        public Savepoint setSavepoint() throws SQLException { return c.setSavepoint(); }
        public Savepoint setSavepoint(String value) throws SQLException { return c.setSavepoint(value); }
        public void rollback(Savepoint value) throws SQLException { c.rollback(value); }
        public void releaseSavepoint(Savepoint value) throws SQLException { c.releaseSavepoint(value); }
        public Statement createStatement(int type,int concurrency,int holdability) throws SQLException { return c.createStatement(type,concurrency,holdability); }
        public PreparedStatement prepareStatement(String sql,int type,int concurrency,int holdability) throws SQLException { return c.prepareStatement(sql,type,concurrency,holdability); }
        public CallableStatement prepareCall(String sql,int type,int concurrency,int holdability) throws SQLException { return c.prepareCall(sql,type,concurrency,holdability); }
        public PreparedStatement prepareStatement(String sql,int keys) throws SQLException { return c.prepareStatement(sql,keys); }
        public PreparedStatement prepareStatement(String sql,int[] columns) throws SQLException { return c.prepareStatement(sql,columns); }
        public PreparedStatement prepareStatement(String sql,String[] columns) throws SQLException { return c.prepareStatement(sql,columns); }
        public Clob createClob() throws SQLException { return c.createClob(); }
        public Blob createBlob() throws SQLException { return c.createBlob(); }
        public NClob createNClob() throws SQLException { return c.createNClob(); }
        public SQLXML createSQLXML() throws SQLException { return c.createSQLXML(); }
        public boolean isValid(int timeout) throws SQLException { return c.isValid(timeout); }
        public void setClientInfo(String name,String value) throws SQLClientInfoException { c.setClientInfo(name,value); }
        public void setClientInfo(Properties values) throws SQLClientInfoException { c.setClientInfo(values); }
        public String getClientInfo(String name) throws SQLException { return c.getClientInfo(name); }
        public Properties getClientInfo() throws SQLException { return c.getClientInfo(); }
        public Array createArrayOf(String name,Object[] values) throws SQLException { return c.createArrayOf(name,values); }
        public Struct createStruct(String name,Object[] values) throws SQLException { return c.createStruct(name,values); }
        public void setSchema(String schema) throws SQLException { c.setSchema(schema); }
        public String getSchema() throws SQLException { return c.getSchema(); }
        public void abort(Executor executor) throws SQLException { c.abort(executor); }
        public void setNetworkTimeout(Executor executor,int millis) throws SQLException { c.setNetworkTimeout(executor,millis); }
        public int getNetworkTimeout() throws SQLException { return c.getNetworkTimeout(); }
        public <T> T unwrap(Class<T> type) throws SQLException { return c.unwrap(type); }
        public boolean isWrapperFor(Class<?> type) throws SQLException { return c.isWrapperFor(type); }
    }
    private static SQLRecoverableException lostReply() { return new SQLRecoverableException("Unit-controlled commit boundary","08006"); }
}
