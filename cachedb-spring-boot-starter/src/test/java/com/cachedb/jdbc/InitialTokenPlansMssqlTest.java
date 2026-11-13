package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Actual MSSQL provider plans for captured production SQL templates; no business/tenant values are cast. */
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class InitialTokenPlansMssqlTest {
    @Test void actualJournalAndSourceControlTemplatesSeekWithoutImplicitColumnConversion()throws Exception{
        try(var admin=CacheTenantContext.administrative();var f=new InitialJournalCleanupProvidersTest.Fixture("mssql")){
            var initial=f.readySqlControl("a").session();f.guard.release(initial.lease());
            var ownerGeneration=new com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation(f.binding.key("b"),1);
            f.guard.provisionClosed(ownerGeneration);
            var ids=JdbcSourceOwnerOnboarding.longIds();
            var ownerPlan=new SourceOwnerOnboarding.Plan(f.binding,"entity_version",ids.formatToken());
            var owner=new JdbcSourceOwnerOnboarding<>(f.guard,ownerPlan,ids,2,Set.of(ownerPlan.fingerprint()));
            var ownerSession=owner.acquire(new SourceOwnerOnboarding.Request(ownerPlan,ownerGeneration,"b",UUID.randomUUID(),
                    ProtectionKey.hash("test-operator"),120));
            try{owner.load(ownerSession);}finally{owner.release(ownerSession);}
            var purge=f.purge("a");
            try{
                var participant=new JdbcInitialBootstrapPurgeParticipant(f.guard,f.binding,"JournalRow",2);
                new JdbcPurgeParticipantJournal(f.guard,2,1).step(purge,1,participant,PurgeParticipant.Phase.CLOSE,1048576);
                seedSelectivity(f);
                var tables=List.of("cachedb_initial_bootstrap","cachedb_initial_page","cachedb_owner_onboarding",
                        "cachedb_purge_scan","cachedb_purge_facade","cachedb_tenant_allocator","cachedb_write_fence");
                var selected=new LinkedHashMap<String,String>();
                for(String template:f.faults.templates())for(String table:tables)
                    if(template.startsWith("SELECT ")&&template.contains(" FROM "+table+" WHERE ")&&!selected.containsKey(table))
                        selected.put(table,template);
                assertEquals(Set.copyOf(tables),selected.keySet(),"Actual production templates must cover every reviewed metadata store");
                var out=Path.of("target","f3-initial-sql-evidence","mssql-plans");Files.createDirectories(out);
                for(var entry:selected.entrySet()){
                    String template=entry.getValue();
                    if(entry.getKey().equals("cachedb_write_fence"))assertTrue(template.contains("fence_key IN (CAST(? AS CHAR(64))"));
                    else assertTrue(template.contains(entry.getKey().equals("cachedb_purge_scan")||entry.getKey().equals("cachedb_tenant_allocator")
                            ?"scope_token=CAST(? AS CHAR(64))":"scope_token=CAST(? AS VARCHAR(64))"));
                    String plan=plan(f,template,initial);
                    verifyPlan(plan);
                    String token=ProtectionKey.hash(template);
                    Files.writeString(out.resolve(token+".sql"),template,StandardCharsets.UTF_8);
                    Files.writeString(out.resolve(token+".xml"),plan,StandardCharsets.UTF_8);
                    System.out.println("INITIAL_SQL_PLAN mssql table="+entry.getKey()+" template="+token+" plan="+InitialBootstrapProof.bytes(plan.getBytes(StandardCharsets.UTF_8)));
                }
            }finally{f.guard.release(purge);}
         }catch(SQLException unsafe){
            System.out.println("INITIAL_SQL_PLAN_FAILURE class="+unsafe.getClass().getName()+" state="+unsafe.getSQLState()+" code="+unsafe.getErrorCode());
            int frames=0;
            for(var frame:unsafe.getStackTrace()){
                if(++frames>8)break;
                System.out.println("INITIAL_SQL_PLAN_FRAME "+frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());
            }
            throw InitialSqlSandbox.safe(unsafe);
        }
    }
    private static void seedSelectivity(InitialJournalCleanupProvidersTest.Fixture f)throws SQLException{
        try(var c=f.sandbox.source.getConnection()){
            c.setAutoCommit(false);
            try(var q=c.prepareStatement("INSERT INTO cachedb_initial_bootstrap(scope_token,tenant_token,operation_id,generation,"
                    +"plan_token,manifest_token,phase_name,revision,payload,payload_bytes) VALUES (?,?,?,1,?,?,'CLOSURE',0,?,1)")){
                q.setQueryTimeout(5);
                for(int i=0;i<2048;i++){
                    q.setString(1,ProtectionKey.hash("plan-fixture-scope-"+i));q.setString(2,ProtectionKey.hash("plan-fixture-tenant-"+i));
                    q.setString(3,UUID.randomUUID().toString());q.setString(4,ProtectionKey.hash("plan"));q.setString(5,ProtectionKey.hash("manifest"));
                    q.setBytes(6,new byte[]{1});q.addBatch();
                    if((i+1)%128==0)assertEquals(128,q.executeBatch().length);
                }
                c.commit();
            }catch(SQLException|RuntimeException|Error failure){c.rollback();throw failure;}
        }
    }
    private static String plan(InitialJournalCleanupProvidersTest.Fixture f,String sql,
            com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session initial)throws Exception{
        try(var c=f.sandbox.source.getConnection();var control=c.createStatement()){
            control.setQueryTimeout(5);control.execute("SET STATISTICS XML ON");
            try(var q=c.prepareStatement(sql)){
                q.setQueryTimeout(5);
                int parameters=(int)sql.chars().filter(ch->ch=='?').count();
                for(int i=1;i<=parameters;i++){
                    if(sql.contains(" FROM cachedb_initial_page ")&&i==4){q.setInt(i,0);continue;}
                    String value=sql.contains(" FROM cachedb_write_fence ")?ProtectionKey.hash("canonical-fence-query-plan")
                            :i==1?initial.lease().key().scopeToken()
                            :i==2?sql.contains(" FROM cachedb_owner_onboarding ")?f.binding.key("b").tenantToken():initial.lease().key().tenantToken()
                            :i==3?initial.request().operationId().toString():ProtectionKey.hash("pending");
                    // Retain real NVARCHAR binds; collect the actual executed prepared query's additional XML result.
                    q.setString(i,value);
                }
                boolean result=q.execute();
                for(int outputs=0;outputs<16;outputs++){
                    if(result){
                        try(var r=q.getResultSet()){
                            var metadata=r.getMetaData();
                            boolean xml=metadata.getColumnCount()==1&&metadata.getColumnLabel(1).toLowerCase(Locale.ROOT).contains("showplan");
                            if(xml){
                                assertTrue(r.next());String plan=boundedXml(r);assertFalse(r.next());return plan;
                            }
                            int rows=0;while(r.next())if(++rows>129)fail("Unexpected metadata row count while collecting actual plan");
                        }
                    }else if(q.getUpdateCount()==-1)break;
                    result=q.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                }
                throw new AssertionError("Actual execution did not return its STATISTICS XML plan");
            }finally{control.execute("SET STATISTICS XML OFF");}
        }
    }
    private static String boundedXml(ResultSet r)throws Exception{
        try(var reader=r.getCharacterStream(1)){
            assertNotNull(reader);var text=new StringBuilder(8192);char[] chunk=new char[4096];
            for(int read;(read=reader.read(chunk))!=-1;){
                if(read==0)continue;
                if(text.length()>1048576-read)fail("Actual plan exceeded the fixed 1MiB export budget");
                text.append(chunk,0,read);
            }
            return text.toString();
        }
    }

    private static void verifyPlan(String xml)throws Exception{
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var converts=document.getElementsByTagNameNS("*","Convert");
        for(int i=0;i<converts.getLength();i++){
            var convert=(org.w3c.dom.Element)converts.item(i);
            if(!"1".equals(convert.getAttribute("Implicit")))continue;
            var columns=convert.getElementsByTagNameNS("*","ColumnReference");
            for(int j=0;j<columns.getLength();j++)
                assertFalse(((org.w3c.dom.Element)columns.item(j)).hasAttribute("Table"),
                        "Implicit conversion on a metadata table column is not accepted");
        }
        int seeks=0;var operators=document.getElementsByTagNameNS("*","RelOp");
        for(int i=0;i<operators.getLength();i++)
            if(((org.w3c.dom.Element)operators.item(i)).getAttribute("PhysicalOp").endsWith("Index Seek"))seeks++;
        assertTrue(seeks>0,"Actual qualified metadata probe must seek; timeout increases are not a fix");
    }
}
