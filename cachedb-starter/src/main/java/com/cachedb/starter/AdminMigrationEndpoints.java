package com.reactor.cachedb.starter;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Migration read/plan endpoints, separate from server lifecycle and async job ownership. */
final class AdminMigrationEndpoints {
    @FunctionalInterface
    interface Parameters {
        Map<String, List<String>> read(HttpExchange exchange) throws IOException;
    }

    @FunctionalInterface
    interface Json {
        void send(HttpExchange exchange, int status, String body) throws IOException;
    }

    @FunctionalInterface
    interface RejectedMethod {
        void send(HttpExchange exchange) throws IOException;
    }

    record Views(
            Function<MigrationPlanner.Template, String> template,
            Function<MigrationSchemaDiscovery.Result, String> discovery,
            Function<Map<String, List<String>>, MigrationPlanner.Request> request,
            BiFunction<MigrationPlanner.Result, MigrationRedisMemoryEstimator.Result, String> plan,
            Function<String, String> escape) {}

    private final CacheDatabaseAdmin admin;
    private final Parameters parameters;
    private final Json json;
    private final RejectedMethod rejected;
    private final Views views;

    AdminMigrationEndpoints(
            CacheDatabaseAdmin admin,
            Parameters parameters,
            Json json,
            RejectedMethod rejected,
            Views views) {
        this.admin = admin;
        this.parameters = parameters;
        this.json = json;
        this.rejected = rejected;
        this.views = views;
    }

    void template(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            rejected.send(exchange);
            return;
        }
        json.send(exchange, 200, views.template.apply(admin.migrationPlannerTemplate()));
    }

    void discovery(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            rejected.send(exchange);
            return;
        }
        try {
            json.send(exchange, 200, views.discovery.apply(admin.discoverMigrationSchema()));
        } catch (IllegalStateException failure) {
            json.send(
                    exchange,
                    500,
                    "{\"error\":\"" + views.escape.apply(failure.getMessage()) + "\"}");
        }
    }

    void plan(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())
                && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            rejected.send(exchange);
            return;
        }
        var plan = admin.planMigration(views.request.apply(parameters.read(exchange)));
        json.send(exchange, 200, views.plan.apply(plan, admin.estimateMigrationRedisMemory(plan)));
    }
}
