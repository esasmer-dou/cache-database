package com.reactor.cachedb.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/** Execution metadata and a durable checkpoint surface for resumable jobs. */
public interface CacheDistributedJobContext {

    String jobId();

    String route();

    int attempt();

    Optional<JsonNode> checkpoint();

    void checkpoint(Object value);
}
