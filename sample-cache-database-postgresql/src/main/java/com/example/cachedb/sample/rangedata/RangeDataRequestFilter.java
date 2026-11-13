package com.example.cachedb.sample.rangedata;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "sample.range-data.enabled", havingValue = "true")
public final class RangeDataRequestFilter extends OncePerRequestFilter {
    private final com.example.cachedb.sample.controls.ControlsAccess access;
    private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(32);
    public RangeDataRequestFilter(com.example.cachedb.sample.controls.ControlsAccess access) { this.access = access; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/examples/v019/range-data/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().length() > 512
                || (request.getQueryString() != null && request.getQueryString().length() > 12288)) {
            response.sendError(414); return;
        }
        try { access.tenant(request.getHeader("Authorization")); }
        catch (org.springframework.web.server.ResponseStatusException denied) {
            response.sendError(denied.getStatusCode().value()); return;
        }
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", "1"); response.sendError(429); return;
        }
        try {
        if (!"PUT".equals(request.getMethod())) { chain.doFilter(request, response); return; }
        byte[] body = request.getInputStream().readNBytes(2049);
        if (body.length > 2048) { response.sendError(413); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new java.io.ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] bytes, int start, int length) { return input.read(bytes, start, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new IllegalStateException("Synchronous bounded request body only");
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
