package com.reactor.cachedb.starter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Resource I/O and token replacement are independent of HTTP routing and job state. */
final class AdminTemplateResources {
    private AdminTemplateResources() {}

    static String load(String path) {
        try (InputStream input =
                AdminTemplateResources.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null)
                throw new IllegalStateException("Missing admin UI resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not load admin UI resource: " + path, failure);
        }
    }

    static String render(String template, String... replacements) {
        if ((replacements.length & 1) != 0)
            throw new IllegalArgumentException(
                    "Template replacements must be provided as token/value pairs");
        String rendered = template;
        for (int i = 0; i < replacements.length; i += 2)
            rendered =
                    rendered.replace(
                            replacements[i],
                            replacements[i + 1] == null ? "" : replacements[i + 1]);
        return rendered;
    }
}
