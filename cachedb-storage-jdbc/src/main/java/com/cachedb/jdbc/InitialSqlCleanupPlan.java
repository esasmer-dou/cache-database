package com.reactor.cachedb.jdbc;

import java.util.*;

/** Immutable fixed SQL descriptors only. No connection, callback, execution or native authority. */
public final class InitialSqlCleanupPlan {
    private InitialSqlCleanupPlan() {}
    public record Column(String name,int width) {}
    public record Store(String table,boolean fixedPair,List<Column> columns) {
        public Store {columns=List.copyOf(columns);}
        public String projection(){return columns.isEmpty()?"operation_id":String.join(",",columns.stream().map(Column::name).toList());}
        public String where(JdbcTenantPurgeDialect dialect){return pair(dialect,fixedPair)+" AND operation_id="+token(dialect,36);}
        public String selectFirst(JdbcTenantPurgeDialect dialect){return limited(dialect,projection(),table,where(dialect),projection());}
        public String selectOperation(JdbcTenantPurgeDialect dialect,boolean after) {
            return limited(dialect,"operation_id",table,pair(dialect,fixedPair)+(after?" AND operation_id>"+token(dialect,36):""),"operation_id");
        }
        public String deleteSelected(JdbcTenantPurgeDialect dialect) {
            var sql=new StringBuilder("DELETE FROM ").append(table).append(" WHERE ").append(where(dialect));
            for(var column:columns)sql.append(" AND ").append(column.name()).append('=').append(parameter(dialect,column.width()));
            return sql.toString();
        }
    }
    private static final List<Store> STORES=List.of(
            new Store("cachedb_initial_page",false,List.of(new Column("page_no",0))),
            new Store("cachedb_purge_work",true,List.of(new Column("work_token",-64))),
            new Store("cachedb_purge_scan",true,List.of(new Column("source_token",-64))),
            new Store("cachedb_purge_facade",false,List.of(new Column("checkpoint_id",96))),
            new Store("cachedb_purge_call",false,List.of()),
            new Store("cachedb_purge_participant",false,List.of(new Column("participant_id",64),new Column("phase_name",16))),
            new Store("cachedb_purge_work_budget",true,List.of()));
    public static List<Store> stores(){return STORES;}
    public static String token(JdbcTenantPurgeDialect dialect,int width){return InitialSqlTokens.varchar(dialect,width);}
    public static String pair(JdbcTenantPurgeDialect dialect,boolean fixed){return InitialSqlTokens.pair(dialect,fixed);}
    private static String parameter(JdbcTenantPurgeDialect dialect,int width) {
        return width==0?"?":width==-64?InitialSqlTokens.char64(dialect):token(dialect,width);
    }
    private static String limited(JdbcTenantPurgeDialect dialect,String projection,String table,String predicate,String order) {
        return "SELECT "+(dialect==JdbcTenantPurgeDialect.MSSQL?"TOP (1) ":"")+projection+" FROM "+table+" WHERE "+predicate
                +" ORDER BY "+order+(dialect==JdbcTenantPurgeDialect.POSTGRES?" LIMIT 1":dialect==JdbcTenantPurgeDialect.ORACLE?" FETCH FIRST 1 ROWS ONLY":"");
    }
}
