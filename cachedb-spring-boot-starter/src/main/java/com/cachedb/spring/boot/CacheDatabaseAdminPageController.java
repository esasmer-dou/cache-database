package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer.DashboardTemplateModel;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Controller
@RequestMapping("${cachedb.admin.base-path:/cachedb-admin}")
final class CacheDatabaseAdminPageController {

    private final CacheDatabaseAdminHttpServer adminHandler;
    private final String basePath;

    CacheDatabaseAdminPageController(CacheDatabaseAdminHttpServer adminHandler, CacheDbSpringProperties properties) {
        this.adminHandler = adminHandler;
        this.basePath = normalizeBasePath(properties.getAdmin().getBasePath());
    }

    @GetMapping({"", "/"})
    public String dashboardRoot(HttpServletRequest request, HttpServletResponse response, Model model) {
        return renderDashboardPage(request, response, model, basePath);
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpServletRequest request, HttpServletResponse response, Model model) {
        return renderDashboardPage(request, response, model, basePath + "/dashboard");
    }

    @GetMapping("/migration-planner")
    public String migrationPlanner(HttpServletRequest request, HttpServletResponse response, Model model) {
        String redirect = redirectToVersionedPath(request, basePath + "/migration-planner");
        if (redirect != null) {
            return redirect;
        }
        applyNoCache(response);
        String language = request.getParameter("lang");
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        DashboardTemplateModel page = adminHandler.renderMigrationPlannerTemplateModel(
                language,
                requestPath,
                encodeQueryString(request)
        );
        model.addAttribute("headMarkup", page.headMarkup());
        model.addAttribute("bodyMarkup", page.bodyMarkup());
        model.addAttribute("htmlLang", page.language());
        return "cachedb-admin/dashboard";
    }

    @GetMapping("/dashboard-v3")
    public String legacyDashboard(HttpServletRequest request) {
        return "redirect:" + appendQuery(basePath, request.getQueryString());
    }

    private String renderDashboardPage(
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            String requestPathForRedirect
    ) {
        String redirect = redirectToVersionedPath(request, requestPathForRedirect);
        if (redirect != null) {
            return redirect;
        }
        applyNoCache(response);
        String language = request.getParameter("lang");
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        DashboardTemplateModel page = adminHandler.renderDashboardTemplateModel(language, requestPath);
        model.addAttribute("headMarkup", page.headMarkup());
        model.addAttribute("bodyMarkup", page.bodyMarkup());
        model.addAttribute("htmlLang", page.language());
        return "cachedb-admin/dashboard";
    }

    private String appendQuery(String path, String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return path;
        }
        return path + "?" + queryString;
    }

    private String redirectToVersionedPath(HttpServletRequest request, String path) {
        String currentVersion = adminHandler.dashboardInstanceId();
        String requestedVersion = request.getParameter("v");
        if (currentVersion.equals(requestedVersion)) {
            return null;
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        request.getParameterMap().forEach((name, values) -> {
            if ("v".equals(name)) {
                return;
            }
            Arrays.stream(values == null ? new String[0] : values).forEach(value -> builder.queryParam(name, value));
        });
        builder.queryParam("v", currentVersion);
        return "redirect:" + builder.build(true).toUriString();
    }
    private String normalizeBasePath(String configuredBasePath) {
        if (configuredBasePath == null || configuredBasePath.isBlank()) {
            return "/cachedb-admin";
        }
        String normalized = configuredBasePath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void applyNoCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }

    private String encodeQueryString(HttpServletRequest request) {
        StringBuilder builder = new StringBuilder();
        request.getParameterMap().forEach((name, values) -> {
            if (values == null || values.length == 0) {
                appendQueryPart(builder, name, "");
                return;
            }
            Arrays.stream(values).forEach(value -> appendQueryPart(builder, name, value));
        });
        return builder.toString();
    }

    private void appendQueryPart(StringBuilder builder, String name, String value) {
        if (builder.length() > 0) {
            builder.append('&');
        }
        builder.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        builder.append('=');
        builder.append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
    }
}
