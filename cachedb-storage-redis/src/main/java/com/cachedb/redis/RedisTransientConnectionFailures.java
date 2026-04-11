package com.reactor.cachedb.redis;

import redis.clients.jedis.exceptions.JedisConnectionException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;

final class RedisTransientConnectionFailures {

    private RedisTransientConnectionFailures() {
    }

    static boolean isTransient(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        Throwable current = throwable;
        while (current != null) {
            if (isTransientType(current) || hasTransientMessage(current.getMessage())) {
                return true;
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null && isTransient(suppressed)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTransientType(Throwable throwable) {
        return throwable instanceof SocketTimeoutException
                || throwable instanceof ConnectException
                || throwable instanceof NoRouteToHostException
                || throwable instanceof ClosedChannelException
                || throwable instanceof JedisConnectionException
                || (throwable instanceof SocketException && hasTransientMessage(throwable.getMessage()));
    }

    private static boolean hasTransientMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("read timed out")
                || normalized.contains("connect timed out")
                || normalized.contains("failed to connect")
                || normalized.contains("broken connection")
                || normalized.contains("connection reset")
                || normalized.contains("unexpected end of stream")
                || normalized.contains("connection refused")
                || normalized.contains("connection closed")
                || normalized.contains("broken pipe");
    }
}
