package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer.DashboardTemplateModel;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;

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
    public String redirectToDashboard() {
        return "redirect:" + basePath + "/dashboard";
    }

    @GetMapping({"/dashboard", "/dashboard-v3"})
    public String dashboard(HttpServletRequest request, Model model) {
        String language = request.getParameter("lang");
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        DashboardTemplateModel page = adminHandler.renderDashboardTemplateModel(language, requestPath);
        model.addAttribute("headMarkup", page.headMarkup());
        model.addAttribute("bodyMarkup", page.bodyMarkup());
        model.addAttribute("htmlLang", page.language());
        return "cachedb-admin/dashboard";
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
}
