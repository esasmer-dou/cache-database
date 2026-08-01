package com.example.cachedb.sample.application;

public final class SampleEntityNotFoundException extends RuntimeException {

    public SampleEntityNotFoundException(String entityName, Object id) {
        super(entityName + " not found in the active set: " + id);
    }
}
