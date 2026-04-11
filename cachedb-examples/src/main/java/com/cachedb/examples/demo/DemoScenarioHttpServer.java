package com.reactor.cachedb.examples.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class DemoScenarioHttpServer implements AutoCloseable {

    private final DemoScenarioService service;
    private final DemoScenarioTuning tuning;
    private final String bindHost;
    private final String publicHost;
    private final int port;
    private final String adminUrl;
    private final DemoScenarioActionQueue actionQueue;
    private HttpServer server;
    private ExecutorService executor;

    public DemoScenarioHttpServer(
            DemoScenarioService service,
            String bindHost,
            String publicHost,
            int port,
            String adminUrl,
            DemoScenarioTuning tuning
    ) {
        this.service = service;
        this.tuning = tuning;
        this.bindHost = bindHost;
        this.publicHost = publicHost;
        this.port = port;
        this.adminUrl = adminUrl;
        this.actionQueue = new DemoScenarioActionQueue();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        executor = Executors.newFixedThreadPool(Math.max(1, tuning.httpWorkerThreads()), new NamedThreadFactory("cachedb-demo-http-"));
        server.setExecutor(executor);
        server.createContext("/", this::handleRoot);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/action-status", this::handleActionStatus);
        server.createContext("/api/views", this::handleViews);
        server.createContext("/api/scenario-shapes", this::handleScenarioShapes);
        server.createContext("/api/scenario-shapes/reset", this::handleResetScenarioShapes);
        server.createContext("/api/seed", this::handleSeed);
        server.createContext("/api/clear", this::handleClear);
        server.createContext("/api/fresh-reset", this::handleFreshReset);
        server.createContext("/api/start", this::handleStart);
        server.createContext("/api/stop", this::handleStop);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://" + publicHost + ":" + server.getAddress().getPort());
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        sendHtml(exchange, DemoScenarioUiSupport.renderPage(tuning, adminUrl, "/api", service.instanceId()));
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        sendJson(exchange, DemoScenarioUiSupport.renderStatus(service, actionQueue.snapshot(), service.instanceId()));
    }

    private void handleActionStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        sendJson(exchange, DemoScenarioUiSupport.renderActionStateOnly(actionQueue.snapshot(), service.instanceId()));
    }

    private void handleViews(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        sendJson(exchange, DemoScenarioUiSupport.renderViews(service));
    }

    private void handleScenarioShapes(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        sendJson(exchange, DemoScenarioUiSupport.renderScenarioShapes(service.scenarioShapeSnapshot()));
    }

    private void handleResetScenarioShapes(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        service.resetScenarioShapeTelemetry();
        sendJson(exchange, DemoScenarioUiSupport.renderScenarioShapes(service.scenarioShapeSnapshot()));
    }

    private void handleSeed(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        actionQueue.enqueueAction("Seed demo verisi hazirlaniyor", service::seedDemoData);
        sendJson(exchange, DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    private void handleStart(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        DemoScenarioLevel level = DemoScenarioUiSupport.parseLevel(exchange.getRequestURI().getRawQuery());
        actionQueue.enqueueAction(level.label() + " seviyesi yuk baslatiliyor", () -> service.startScenario(level));
        sendJson(exchange, DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    private void handleClear(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        actionQueue.enqueueAction("Tum demo verisi temizleniyor", service::clearDemoData);
        sendJson(exchange, DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    private void handleFreshReset(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        actionQueue.enqueueAction("Redis, PostgreSQL ve demo telemetrisi sifirlaniyor", service::resetEnvironment);
        sendJson(exchange, DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    private void handleStop(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        actionQueue.enqueuePriorityStop("Yuk durduruluyor", service::stopScenario);
        sendJson(exchange, DemoScenarioUiSupport.renderActionAck(actionQueue.snapshot(), service.instanceId()));
    }

    private void sendJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sendHtml(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        service.close();
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        actionQueue.close();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicLong sequence = new AtomicLong();

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
