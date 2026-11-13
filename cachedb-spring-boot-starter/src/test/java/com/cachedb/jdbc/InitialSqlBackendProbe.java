package com.reactor.cachedb.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Test diagnostics only. The captured connection still physically owns the paused SQL transaction. */
final class InitialSqlBackendProbe {
    private InitialSqlBackendProbe() {}

    record Session(JdbcTenantPurgeDialect dialect,int id,long auditSession,int containerId,int instanceId) {}
    record Observation(Session session,long serial,String ownedScope) {}

    static Session capture(Connection physical,JdbcTenantPurgeDialect dialect)throws SQLException {
        String sql=switch(dialect) {
            case POSTGRES -> "SELECT pg_backend_pid()";
            case MSSQL -> "SELECT @@SPID";
            case ORACLE -> "SELECT TO_NUMBER(SYS_CONTEXT('USERENV','SID')),"
                    +"TO_NUMBER(SYS_CONTEXT('USERENV','SESSIONID')),"
                    +"TO_NUMBER(SYS_CONTEXT('USERENV','CON_ID')),"
                    +"TO_NUMBER(SYS_CONTEXT('USERENV','INSTANCE')) FROM dual";
        };
        try(var q=prepare(physical,sql,2);var rows=q.executeQuery()) {
            if(!rows.next())throw invalid("Missing owned backend");
            int id=positiveInt(rows,1);long audit=0;int container=0,instance=0;
            if(dialect==JdbcTenantPurgeDialect.ORACLE) {
                audit=positiveLong(rows,2);container=positiveInt(rows,3);instance=positiveInt(rows,4);
            }
            if(rows.next())throw invalid("Non-unique owned backend");
            return new Session(dialect,id,audit,container,instance);
        }catch(SQLException failure){throw unavailable(dialect,failure);}
    }

    static Observation bind(Connection administrator,Session session,String ownedScope)throws SQLException {
        if(session==null||session.id()<=0||ownedScope==null||!ownedScope.matches("cachedb_v019_initial_[0-9a-f]{8}"))
            throw invalid("Invalid owned diagnostic scope");
        if(session.dialect()!=JdbcTenantPurgeDialect.ORACLE)return new Observation(session,0,ownedScope);
        // SYSTEM resolves SERIAL# against SID + AUDSID captured on the still-held physical connection.
        // The instance predicate deliberately fails closed for a different RAC instance; RAC is not certified.
        String sql="SELECT serial# FROM v$session WHERE sid=? AND audsid=? AND con_id=? AND username=UPPER(?)"
                +" AND TO_NUMBER(SYS_CONTEXT('USERENV','INSTANCE'))=?";
        try(var q=prepare(administrator,sql,2)) {
            q.setInt(1,session.id());q.setLong(2,session.auditSession());q.setInt(3,session.containerId());
            q.setString(4,ownedScope);q.setInt(5,session.instanceId());
            try(var rows=q.executeQuery()) {
                if(!rows.next())throw invalid("Owned Oracle session unavailable on this instance");
                long serial=positiveLong(rows,1);
                if(rows.next())throw invalid("Non-unique owned Oracle session");
                return new Observation(session,serial,ownedScope);
            }
        }catch(SQLException failure){throw unavailable(session.dialect(),failure);}
    }

    static boolean hasBlockedWaiter(Connection administrator,Observation observation)throws SQLException {
        Session session=observation.session();
        String sql=switch(session.dialect()) {
            case POSTGRES -> "SELECT COUNT(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))";
            case MSSQL -> "SELECT COUNT(*) FROM sys.dm_exec_requests WHERE blocking_session_id=? AND database_id=DB_ID(?)";
            case ORACLE -> "SELECT COUNT(*) FROM v$session waiter JOIN v$session blocker ON blocker.sid=waiter.blocking_session"
                    +" WHERE blocker.sid=? AND blocker.serial#=? AND blocker.audsid=? AND blocker.con_id=?"
                    +" AND blocker.username=UPPER(?) AND waiter.blocking_session_status='VALID' AND waiter.status='ACTIVE'"
                    +" AND waiter.blocking_instance=? AND waiter.con_id=blocker.con_id AND waiter.username=blocker.username"
                    +" AND TO_NUMBER(SYS_CONTEXT('USERENV','INSTANCE'))=?";
        };
        try(var q=prepare(administrator,sql,1)) {
            q.setInt(1,session.id());
            if(session.dialect()==JdbcTenantPurgeDialect.MSSQL)q.setString(2,observation.ownedScope());
            else if(session.dialect()==JdbcTenantPurgeDialect.ORACLE) {
                q.setLong(2,observation.serial());q.setLong(3,session.auditSession());q.setInt(4,session.containerId());
                q.setString(5,observation.ownedScope());q.setInt(6,session.instanceId());q.setInt(7,session.instanceId());
            }
            try(var rows=q.executeQuery()) {
                if(!rows.next())throw invalid("Missing physical SQL waiter result");
                long blocked=rows.getLong(1);
                if(rows.wasNull()||blocked<0)throw invalid("Malformed physical SQL waiter count");
                return blocked>0;
            }
        }catch(SQLException failure){throw unavailable(session.dialect(),failure);}
    }

    private static PreparedStatement prepare(Connection c,String sql,int rows)throws SQLException {
        var q=c.prepareStatement(sql);
        try {q.setQueryTimeout(2);q.setFetchSize(1);q.setMaxRows(rows);return q;}
        catch(SQLException failure){try{q.close();}catch(SQLException ignored){}throw failure;}
    }
    private static int positiveInt(ResultSet rows,int column)throws SQLException {
        int value=rows.getInt(column);if(rows.wasNull()||value<=0)throw invalid("Malformed owned backend identity");return value;
    }
    private static long positiveLong(ResultSet rows,int column)throws SQLException {
        long value=rows.getLong(column);if(rows.wasNull()||value<=0)throw invalid("Malformed owned backend identity");return value;
    }
    private static SQLException invalid(String message){return new SQLException(message,"CDBTP");}
    private static SQLException unavailable(JdbcTenantPurgeDialect dialect,SQLException failure) {
        // A denied diagnostic is a test failure, never an assumption, skip, fake waiter or provider fallback.
        return new SQLException("INITIAL "+dialect+" physical SQL diagnostic failed",failure.getSQLState(),failure.getErrorCode());
    }
}
