package com.example.cachedb.sample.web;

final class ApiLimits {

    private ApiLimits() {
    }

    static int requireInRange(String parameter, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    parameter + " must be between " + min + " and " + max + "; received " + value
            );
        }
        return value;
    }
}
