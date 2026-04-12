package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer.DashboardTemplateModel;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
        return renderDashboardPage(request, response, model);
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpServletRequest request, HttpServletResponse response, Model model) {
        return renderDashboardPage(request, response, model);
    }

    @GetMapping("/migration-planner")
    public String migrationPlanner(HttpServletRequest request, HttpServletResponse response, Model model) {
        applyNoCache(response);
        String language = request.getParameter("lang");
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        DashboardTemplateModel page = adminHandler.renderMigrationPlannerTemplateModel(language, requestPath);
        model.addAttribute("headMarkup", page.headMarkup());
        model.addAttribute("bodyMarkup", page.bodyMarkup());
        model.addAttribute("htmlLang", page.language());
        return "cachedb-admin/dashboard";
    }

    @GetMapping("/dashboard-v3")
    public String legacyDashboard(HttpServletRequest request) {
        return "redirect:" + appendQuery(basePath, request.getQueryString());
    }

    private String renderDashboardPage(HttpServletRequest request, HttpServletResponse response, Model model) {
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
}
