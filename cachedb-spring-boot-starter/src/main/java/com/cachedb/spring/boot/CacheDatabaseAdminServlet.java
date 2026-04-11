package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer.AdminHttpResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.util.List;

final class CacheDatabaseAdminServlet extends HttpServlet {

    private static final List<String> SKIPPED_RESPONSE_HEADERS = List.of(
            "connection",
            "content-length",
            "date",
            "host"
    );

    private final CacheDatabaseAdminHttpServer adminHandler;
    private final String basePath;

    CacheDatabaseAdminServlet(CacheDatabaseAdminHttpServer adminHandler, String basePath) {
        this.adminHandler = adminHandler;
        this.basePath = normalizeBasePath(basePath);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isBasePathRequest(request)) {
            response.sendRedirect(request.getContextPath() + basePath + "/dashboard");
            return;
        }
        URI targetUri = resolveTargetUri(request);
        byte[] requestBody = request.getInputStream().readAllBytes();
        AdminHttpResponse adminResponse = adminHandler.dispatch(request.getMethod(), targetUri, requestBody);
        response.setStatus(adminResponse.statusCode());
        copyResponseHeaders(request, response, adminResponse.headers());
        byte[] body = adminResponse.body();
        if (body.length > 0) {
            response.getOutputStream().write(body);
        }
    }

    private URI resolveTargetUri(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(basePath)) {
            path = path.substring(basePath.length());
        }
        if (path.isBlank() || "/".equals(path)) {
            path = "/dashboard";
        }
        String query = request.getQueryString();
        String uri = path;
        if (query != null && !query.isBlank()) {
            uri = uri + "?" + query;
        }
        return URI.create(uri);
    }

    private boolean isBasePathRequest(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return requestPath.equals(basePath) || requestPath.equals(basePath + "/");
    }

    private void copyResponseHeaders(
            HttpServletRequest request,
            HttpServletResponse response,
            java.util.Map<String, List<String>> headers
    ) {
        for (java.util.Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            if (SKIPPED_RESPONSE_HEADERS.stream().anyMatch(it -> it.equalsIgnoreCase(name))) {
                continue;
            }
            for (String value : entry.getValue()) {
                if ("location".equalsIgnoreCase(name)) {
                    response.addHeader(name, rewriteLocation(request, value));
                } else {
                    response.addHeader(name, value);
                }
            }
        }
    }

    private String rewriteLocation(HttpServletRequest request, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (value.startsWith("/")) {
            return request.getContextPath() + basePath + value;
        }
        return value;
    }

    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank() || "/".equals(basePath.trim())) {
            return "";
        }
        String normalized = basePath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
