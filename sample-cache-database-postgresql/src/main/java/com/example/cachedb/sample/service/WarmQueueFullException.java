package com.example.cachedb.sample.service;

public class WarmQueueFullException extends RuntimeException {

    public WarmQueueFullException(String message) {
        super(message);
    }
}
