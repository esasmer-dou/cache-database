package com.reactor.cachedb.core.queue;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class WorkerErrorCapture {

    private static final int MAX_STACK_CHARS = 12_000;

    private WorkerErrorCapture() {
    }

    public static WorkerErrorDetails capture(Throwable throwable) {
        if (throwable == null) {
            return new WorkerErrorDetails("", "", "", "", "", "");
        }
        Throwable root = rootCause(throwable);
        return new WorkerErrorDetails(
                throwable.getClass().getName(),
                defaultString(throwable.getMessage()),
                root.getClass().getName(),
                defaultString(root.getMessage()),
                resolveOrigin(throwable, root),
                renderStackTrace(throwable)
        );
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String resolveOrigin(Throwable throwable, Throwable root) {
        String origin = findApplicationFrame(root.getStackTrace());
        if (!origin.isBlank()) {
            return origin;
        }
        origin = findApplicationFrame(throwable.getStackTrace());
        if (!origin.isBlank()) {
            return origin;
        }
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        if (stackTrace == null || stackTrace.length == 0) {
            return "";
        }
        return formatFrame(stackTrace[0]);
    }

    private static String findApplicationFrame(StackTraceElement[] stackTrace) {
        if (stackTrace == null) {
            return "";
        }
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (className != null && className.startsWith("com.reactor.cachedb.")) {
                return formatFrame(element);
            }
        }
        return "";
    }

    private static String formatFrame(StackTraceElement element) {
        return element.getClassName()
                + "#"
                + element.getMethodName()
                + ":"
                + element.getLineNumber();
    }

    private static String renderStackTrace(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        String raw = buffer.toString();
        if (raw.length() <= MAX_STACK_CHARS) {
            return raw;
        }
        return raw.substring(0, MAX_STACK_CHARS) + System.lineSeparator() + "... [truncated]";
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
