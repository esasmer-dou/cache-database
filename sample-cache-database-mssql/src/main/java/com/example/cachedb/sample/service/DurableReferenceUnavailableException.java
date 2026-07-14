package com.example.cachedb.sample.service;

public class DurableReferenceUnavailableException extends RuntimeException {

    public DurableReferenceUnavailableException(String message) {
        super(message);
    }
}
