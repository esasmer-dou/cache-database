package com.example.cachedb.sample.encryption;

import com.example.cachedb.sample.controls.ControlsAccess;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("demo & encryption")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class EncryptedNoteRequestFilter extends OncePerRequestFilter {
    private final ControlsAccess access;
    private final Semaphore admission = new Semaphore(8);
    public EncryptedNoteRequestFilter(ControlsAccess access) { this.access = access; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/examples/v019/encryption/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try { EncryptedNoteController.verify(access, request.getHeader("Authorization"), request.getHeader("X-CacheDB-Causal")); }
        catch (ResponseStatusException rejected) { response.sendError(rejected.getStatusCode().value(), rejected.getReason()); return; }
        if (request.getRequestURI().length() > 256 || request.getQueryString() != null) {
            response.sendError(400, "This API has bounded paths and no query parameters"); return;
        }
        if (!admission.tryAcquire()) { response.sendError(429, "Eight encryption requests are already active"); return; }
        try {
            if (!request.getMethod().equals("PUT") && !request.getMethod().equals("POST")) {
                chain.doFilter(request, response); return;
            }
            if (request.getContentLengthLong() > 2048) { response.sendError(413); return; }
            byte[] body = request.getInputStream().readNBytes(2049);
            if (body.length > 2048) { response.sendError(413); return; }
            var input = new ByteArrayInputStream(body);
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    return new ServletInputStream() {
                        public int read() { return input.read(); }
                        public int read(byte[] b, int offset, int length) { return input.read(b, offset, length); }
                        public boolean isFinished() { return input.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous MVC body"); }
                    };
                }
            }, response);
        } finally { admission.release(); }
    }
}
