package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Compiles ordinary application code; no reflective access or simulated closure evidence. */
@Timeout(30)
class CacheInitialBootstrapApiBoundaryTest {
    @TempDir Path output;

    @Test void applicationsUseTheRootOwnedNamespaceEntryPoint() throws Exception {
        var result = compile("ApplicationBootstrap", """
                package consumer;
                import com.reactor.cachedb.starter.*;
                import com.reactor.cachedb.redis.RedisTenantRuntimeControl.ApprovedRoster;
                import java.util.Set;
                final class ApplicationBootstrap {
                    CacheInitialTenantBootstrap<Object, Long> create(
                            CacheInitialNamespace<Object, Long> namespace,
                            CacheTenantRuntimeEnrollment enrollment,
                            RedisTenantPurgePlan plan, ApprovedRoster roster) {
                        return namespace.createBootstrap(enrollment, plan, roster, 8, Set.of());
                    }
                }
                """);
        assertTrue(result.compiled(), () -> result.diagnostics().toString());
    }

    @Test void applicationsCannotSupplyTheNativeClosureCapability() throws Exception {
        var result = compile("ForgedBootstrap", """
                package consumer;
                import com.reactor.cachedb.starter.CacheInitialTenantBootstrap;
                import java.util.Set;
                final class ForgedBootstrap {
                    Object create() {
                        return CacheInitialTenantBootstrap.create(null, null, null, null, null,
                                null, null, 1, 1024, 512, 4096L, 8, Set.of());
                    }
                }
                """);
        assertFalse(result.compiled());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.getKind() == Diagnostic.Kind.ERROR
                        && diagnostic.getMessage(Locale.ROOT).contains("cannot be accessed from outside package")),
                () -> result.diagnostics().toString());
    }

    @Test void applicationsProvisionAnIdentityDomainOnlyThroughTheRootAdministrativeEntry() throws Exception {
        var result = compile("ApplicationIdentityDomain", """
                package consumer;
                import com.reactor.cachedb.starter.CacheInitialTenantBootstrap;
                import com.reactor.cachedb.core.protection.FreshIdentityDomain;
                import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Request;
                import com.reactor.cachedb.jdbc.JdbcFreshIdentityAllocator.Status;
                final class ApplicationIdentityDomain {
                    Status provision(CacheInitialTenantBootstrap<?, ?> bootstrap,
                            Request request, FreshIdentityDomain domain) {
                        return bootstrap.provisionIdentityDomain(request, domain);
                    }
                }
                """);
        assertTrue(result.compiled(), () -> result.diagnostics().toString());
    }

    @Test void applicationsCannotFinalizeJdbcWithoutActualNativeObservation() throws Exception {
        var result=compile("DirectJdbcFinalization","""
                package consumer;
                import com.reactor.cachedb.jdbc.JdbcInitialTenantBootstrap;
                import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
                final class DirectJdbcFinalization {
                    void finish(JdbcInitialTenantBootstrap<?,?> sql,Session session,Activation activation) {
                        sql.active(session,activation);
                    }
                }
                """);
        assertFalse(result.compiled(),"A valid SQL session/DTO must not expose native finalization authority");
        assertTrue(result.diagnostics().stream().anyMatch(d->d.getKind()==Diagnostic.Kind.ERROR));
    }
    @Test void applicationsRequestFreshAdmissionWithoutSupplyingAReservedRange() throws Exception {
        var result = compile("FreshAdmission", """
                package consumer;
                import com.reactor.cachedb.starter.CacheInitialTenantBootstrap;
                import com.reactor.cachedb.core.protection.FreshIdentityRange;
                final class FreshAdmission {
                    FreshIdentityRange admit(CacheInitialTenantBootstrap<?, ?> bootstrap,
                            String tenant, FreshIdentityRange.Request request) {
                        return bootstrap.reserveAndAdmitFreshRange(tenant, request);
                    }
                }
                """);
        assertTrue(result.compiled(), () -> result.diagnostics().toString());
    }
    @Test void applicationsCannotConstructTheNativeFreshAdmissionCapability() throws Exception {
        var result = compile("ForgedFreshAdmission", """
                package consumer;
                import com.reactor.cachedb.starter.CacheFreshIdentityAdmission;
                final class ForgedFreshAdmission {
                    Object forge() { return new CacheFreshIdentityAdmission(null, null, null, null, null, null); }
                }
                """);
        assertFalse(result.compiled());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
    }
    @Test void applicationsCannotNameOrConstructTheRootPrivateObservation() throws Exception {
        var result=compile("ForgedOpenObservation","""
                package consumer;
                import com.reactor.cachedb.starter.CacheInitialNativeBridge.OpenObservation;
                import com.reactor.cachedb.starter.CacheInitialActivationCommit;
                final class ForgedOpenObservation {
                    Object forge(){return new OpenObservation(null,null,null,null,null);}
                }
                """);
        assertFalse(result.compiled());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.getKind()==Diagnostic.Kind.ERROR));
    }
    @Test void applicationsMayReadPureJournalAndCleanupDataWithoutACommitHandle() throws Exception {
        var result=compile("JournalData","""
                package consumer;
                import com.reactor.cachedb.jdbc.*;
                import com.reactor.cachedb.core.protection.ProtectionKey;
                final class JournalData {
                    Object read(byte[] data,ProtectionKey key) {
                        var frame=InitialBootstrapJournalCodec.decode(data,key);
                        return new Object[]{frame.phase(),InitialSqlCleanupPlan.stores()};
                    }
                }
                """);
        assertTrue(result.compiled(),()->result.diagnostics().toString());
    }
    private Compilation compile(String name, String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "The API boundary check requires JDK 21, not a JRE");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///consumer/" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            boolean compiled = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:none", "-classpath", classPath,
                            "-d", output.toString()), null, List.of(unit)).call();
            return new Compilation(compiled, List.copyOf(diagnostics.getDiagnostics()));
        }
    }

    private record Compilation(boolean compiled, List<Diagnostic<? extends JavaFileObject>> diagnostics) {}
}
