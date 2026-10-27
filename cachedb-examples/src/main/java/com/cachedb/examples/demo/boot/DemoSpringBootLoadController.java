package com.reactor.cachedb.examples.demo.boot;

import com.reactor.cachedb.examples.demo.DemoScenarioActionQueue;
import com.reactor.cachedb.examples.demo.DemoScenarioLevel;
import com.reactor.cachedb.examples.demo.DemoScenarioService;
import com.reactor.cachedb.examples.demo.DemoScenarioTuning;
import com.reactor.cachedb.examples.demo.DemoScenarioUiSupport;
import com.reactor.cachedb.spring.boot.CacheDbSpringProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping
public class DemoSpringBootLoadController implements DisposableBean {

    private final DemoScenarioService service;
    private final DemoScenarioTuning tuning;
    private final DemoScenarioActionQueue actionQueue;
    private final String adminDashboardUrl;

    public DemoSpringBootLoadController(
            DemoScenarioService service,
            DemoScenarioTuning tuning,
            CacheDbSpringProperties springProperties
    ) {
        this.service = service;
        this.tuning = tuning;
        this.actionQueue = new DemoScenarioActionQueue();
        String basePath = normalizeBasePath(springProperties.getAdmin().getBasePath());
        this.adminDashboardUrl = basePath + "?lang=tr";
    }

    @GetMapping(value = {"/", "/demo-load"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> page() {
        return noStoreHtml(DemoScenarioUiSupport.renderPage(tuning, adminDashboardUrl, "/demo-load/api", service.instanceId()));
    }

    @GetMapping(value = "/demo-load/api/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> status() {
        return noStoreJson(DemoScenarioUiSupport.renderStatus(service, actionQueue.snapshot(), service.instanceId()));
    }

    @GetMapping(value = "/demo-load/api/action-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> actionStatus() {
        return noStoreJson(DemoScenarioUiSupport.renderActionStateOnly(actionQueue.snapshot(), service.instanceId()));
    }

    @GetMapping(value = "/demo-load/api/views", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> views() {
        return noStoreJson(DemoScenarioUiSupport.renderViews(service));
    }

    @GetMapping(value = "/demo-load/api/scenario-shapes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> scenarioShapes() {
        return noStoreJson(DemoScenarioUiSupport.renderScenarioShapes(service.scenarioShapeSnapshot()));
    }

    @PostMapping(value = "/demo-load/api/scenario-shapes/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> resetScenarioShapes() {
        service.resetScenarioShapeTelemetry();
        return noStoreJson(DemoScenarioUiSupport.renderScenarioShapes(service.scenarioShapeSnapshot()));
    }

    @PostMapping(value = "/demo-load/api/seed", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> seed() {
        actionQueue.enqueueAction("Seed demo verisi hazirlaniyor", service::seedDemoData);
        return ResponseEntity.ok(DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    @PostMapping(value = "/demo-load/api/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> start(
            @RequestParam(name = "level", defaultValue = "LOW") DemoScenarioLevel level,
            @RequestParam(name = "readers", required = false) Integer readerThreads,
            @RequestParam(name = "writers", required = false) Integer writerThreads
    ) {
        actionQueue.enqueueAction(level.label() + " seviyesi yuk baslatiliyor", () -> service.startScenario(level, readerThreads, writerThreads));
        return ResponseEntity.ok(DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    @PostMapping(value = "/demo-load/api/clear", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> clear() {
        actionQueue.enqueuePriorityAction("Tum demo verisi temizleniyor", service::clearDemoData);
        return ResponseEntity.ok(DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    @PostMapping(value = "/demo-load/api/fresh-reset", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> freshReset() {
        actionQueue.enqueuePriorityAction("Redis, PostgreSQL ve demo telemetrisi sifirlaniyor", service::resetEnvironment);
        return ResponseEntity.ok(DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    @PostMapping(value = "/demo-load/api/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> stop() {
        actionQueue.enqueuePriorityStop("Yuk durduruluyor", service::stopScenario);
        return ResponseEntity.ok(DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    @Override
    public void destroy() {
        actionQueue.close();
    }

    private String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "/cachedb-admin";
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

    private ResponseEntity<String> noStoreHtml(String body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ZERO).mustRevalidate().noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(body);
    }

    private ResponseEntity<String> noStoreJson(String body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ZERO).mustRevalidate().noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
