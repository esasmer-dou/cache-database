package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class InitialSourcePagesFixtureCleanupTest {
    @Test
    void checkedConstructionFailureClosesDatasourceAndRemainsPrimary() {
        var original=new SQLException("construction");
        var source=new FailingDataSource(original,null);
        assertSame(original,assertThrows(SQLException.class,()->construct(source)));
        assertEquals(1,source.closes);
        assertEquals(0,original.getSuppressed().length);
    }

    @Test
    void cleanupFailureIsSuppressedRatherThanReplacingConstructionFailure() {
        var original=new SQLException("construction");
        var cleanup=new IllegalStateException("cleanup");
        var source=new FailingDataSource(original,cleanup);
        assertSame(original,assertThrows(SQLException.class,()->construct(source)));
        assertEquals(1,source.closes);
        assertArrayEquals(new Throwable[]{cleanup},original.getSuppressed());
    }

    @Test
    void fatalConstructionAndCleanupFailuresPreserveOriginalIdentity() {
        var original=new AssertionError("construction");
        var cleanup=new AssertionError("cleanup");
        var source=new FailingDataSource(original,cleanup);
        assertSame(original,assertThrows(AssertionError.class,()->construct(source)));
        assertEquals(1,source.closes);
        assertArrayEquals(new Throwable[]{cleanup},original.getSuppressed());
    }

    @Test
    void identicalConstructionAndCleanupFailureDoesNotSelfSuppress() {
        var original=new IllegalStateException("same failure");
        var source=new FailingDataSource(original,original);
        assertSame(original,assertThrows(IllegalStateException.class,()->construct(source)));
        assertEquals(1,source.closes);
        assertEquals(0,original.getSuppressed().length);
    }

    private static void construct(HikariDataSource source)throws Exception {
        new InitialSourcePagesProvidersTest.Fixture("postgres","0",false,config->source);
    }

    private static final class FailingDataSource extends HikariDataSource {
        private final Throwable constructionFailure;
        private final Throwable cleanupFailure;
        int closes;

        FailingDataSource(Throwable constructionFailure,Throwable cleanupFailure) {
            this.constructionFailure=constructionFailure;
            this.cleanupFailure=cleanupFailure;
        }

        @Override public Connection getConnection()throws SQLException {
            if(constructionFailure instanceof SQLException checked)throw checked;
            if(constructionFailure instanceof RuntimeException unchecked)throw unchecked;
            if(constructionFailure instanceof Error fatal)throw fatal;
            throw new AssertionError("Missing injected construction failure");
        }

        @Override public void close() {
            closes++;
            if(cleanupFailure instanceof RuntimeException unchecked)throw unchecked;
            if(cleanupFailure instanceof Error fatal)throw fatal;
        }
    }
}
