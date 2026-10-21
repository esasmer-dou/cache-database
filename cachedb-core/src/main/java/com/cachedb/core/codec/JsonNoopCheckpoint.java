package com.reactor.cachedb.core.codec;

/** The convenience encoder needs no lambda-metafactory bootstrap for an empty callback. */
final class JsonNoopCheckpoint implements Runnable {
    static final JsonNoopCheckpoint INSTANCE = new JsonNoopCheckpoint();

    private JsonNoopCheckpoint() {}

    @Override
    public void run() {}
}
