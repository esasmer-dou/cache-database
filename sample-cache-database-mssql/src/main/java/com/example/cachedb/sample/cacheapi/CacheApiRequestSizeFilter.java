package com.example.cachedb.sample.cacheapi;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON before materialization, including chunked commands. */
@Component
@ConditionalOnProperty(prefix = "sample.cache-api", name = "enabled", havingValue = "true")
public final class CacheApiRequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 4096;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/examples/v019/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String query = request.getQueryString();
        if (query != null && query.length() > 4096) { response.sendError(414, "Query is too long"); return; }
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method) && !"PATCH".equals(method)) {
            chain.doFilter(request, response); return;
        }
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.sendError(413, "Cache API bodies are limited to 4 KiB"); return;
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) { response.sendError(413, "Cache API bodies are limited to 4 KiB"); return; }
        var input = new ByteArrayInputStream(bytes);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("Synchronous MVC request body");
                    }
                };
            }
        }, response);
    }
}
