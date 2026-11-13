package com.example.cachedb.sample.audit;

import com.example.cachedb.sample.controls.ControlsAccess;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "sample.audit-api.enabled", havingValue = "true")
public final class AuditRequestFilter extends OncePerRequestFilter {
    private final ControlsAccess access;
    private final Semaphore permits = new Semaphore(8);
    public AuditRequestFilter(ControlsAccess access) { this.access = access; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/examples/v019/audit-notes") && !path.startsWith("/api/examples/v019/audit-notes/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try { AuditNoteRules.verify(access, request.getHeader("Authorization"), request.getHeader("X-CacheDB-Causal")); }
        catch (org.springframework.web.server.ResponseStatusException denied) {
            response.sendError(denied.getStatusCode().value()); return;
        }
        if (request.getRequestURI().length() > 256 || request.getQueryString() != null) {
            response.sendError(414); return;
        }
        if (!permits.tryAcquire()) { response.setHeader("Retry-After", "1"); response.sendError(429); return; }
        try {
            if (request.getContentLengthLong() > 2048) { response.sendError(413); return; }
            byte[] body = request.getInputStream().readNBytes(2049);
            if (body.length > 2048) { response.sendError(413); return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new java.io.ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public int read(byte[] data, int off, int len) { return input.read(data, off, len); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) {
                            throw new IllegalStateException("Synchronous bounded audit request only");
                        }
                    };
                }
                @Override public java.io.BufferedReader getReader() {
                    return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }, response);
        } finally { permits.release(); }
    }
}
