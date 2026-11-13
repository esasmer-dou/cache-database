package com.example.cachedb.purge;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.reactor.cachedb.core.protection.ProtectionException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.web.filter.OncePerRequestFilter;

/** Synchronous response delivery stays inside the native enrollment callback until flush returns. */
final class TenantPurgeHttp extends OncePerRequestFilter {
    static final String ROOT = "/api/examples/v019/tenant-purge";
    private final TenantPurgeRuntime runtime;
    private final ObjectMapper mapper;
    private final ObjectReader requestReader;
    private final byte[] expectedAuthorization;
    private final String run;
    private final Semaphore admissions = new Semaphore(8);

    TenantPurgeHttp(TenantPurgeRuntime runtime, ObjectMapper mapper, String token, String run) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{32,128}") || !run.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Bounded random admin token and fresh run required");
        this.runtime = runtime; this.mapper = mapper; this.run = run;
        expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
        requestReader = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readerFor(PurgeRequest.class);
    }

    record PurgeRequest(String confirmation, String operationId) {}

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(ROOT);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        String authorization = singleHeader(request, "Authorization");
        if (authorization == null) { error(response, 401, "AUTH_REQUIRED"); return; }
        if (authorization.length() > 256 || !MessageDigest.isEqual(expectedAuthorization,
                authorization.getBytes(StandardCharsets.US_ASCII))) {
            error(response, 403, "AUTH_REJECTED"); return;
        }
        if (!run.equals(singleHeader(request, "X-Purge-Run"))) {
            error(response, 400, "RUN_MISMATCH"); return;
        }
        if (request.isAsyncStarted() || request.getQueryString() != null || request.getRequestURI().length() > 256) {
            error(response, 400, "BOUNDED_SYNCHRONOUS_REQUEST_REQUIRED"); return;
        }
        if (!admissions.tryAcquire()) { error(response, 429, "BUSY"); return; }
        try {
            String path = request.getRequestURI(), method = request.getMethod();
            if ("GET".equals(method) && (request.getContentLengthLong() > 0
                    || request.getHeader("Transfer-Encoding") != null)) {
                error(response, 400, "UNEXPECTED_BODY"); return;
            }
            if ("GET".equals(method) && (ROOT + "/status").equals(path)) {
                writeAndFlush(response, runtime.status());
            } else if ("GET".equals(method) && path.matches(ROOT + "/rows/[1-7]")) {
                long id = path.charAt(path.length() - 1) - '0';
                runtime.deliver(id, row -> {
                    try { writeAndFlush(response, row); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                });
            } else if ("POST".equals(method) && (ROOT + "/purge").equals(path)) {
                if (request.getContentType() == null
                        || !request.getContentType().toLowerCase(Locale.ROOT).matches("application/json(;.*)?")) {
                    error(response, 415, "JSON_REQUIRED"); return;
                }
                byte[] body = request.getInputStream().readNBytes(513);
                if (body.length == 0 || body.length > 512) { error(response, 413, "BODY_BUDGET"); return; }
                PurgeRequest input = requestReader.readValue(body);
                if (input == null || !"ERASE_OWNED_ROWS".equals(input.confirmation())
                        || input.operationId() == null || input.operationId().length() != 36)
                    throw new IllegalArgumentException();
                UUID operation = UUID.fromString(input.operationId());
                if (!operation.toString().equals(input.operationId())) throw new IllegalArgumentException();
                writeAndFlush(response, runtime.purge(operation));
            } else { error(response, 404, "FIXED_ROUTE_REQUIRED"); }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            if (!response.isCommitted()) error(response, 400, "INVALID_REQUEST");
        } catch (ProtectionException failure) {
            if (!response.isCommitted()) error(response, 409, "PROTECTION_CLOSED_OR_INCOMPLETE");
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        } catch (RuntimeException failure) {
            if (!response.isCommitted()) error(response, 503, "UNAVAILABLE_PRESERVE_CLOSED_STATE");
        } finally { admissions.release(); }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String result = values.nextElement();
        return values.hasMoreElements() ? "" : result;
    }

    private void writeAndFlush(HttpServletResponse response, Object value) throws IOException {
        // Do not close the servlet stream: native delivery retains custody through the physical flush.
        mapper.writer().without(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                .writeValue(response.getOutputStream(), value);
        response.flushBuffer();
    }
    private void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); writeAndFlush(response, Map.of("code", code));
    }
}

