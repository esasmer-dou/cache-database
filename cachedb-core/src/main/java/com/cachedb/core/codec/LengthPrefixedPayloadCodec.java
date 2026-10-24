package com.reactor.cachedb.core.codec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class LengthPrefixedPayloadCodec {
    public static final int MAX_FIELDS = 4096;
    public static final int MAX_PAYLOAD_CHARS = 8 * 1024 * 1024;

    private LengthPrefixedPayloadCodec() {
    }

    public static String encode(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        if (values.size() > MAX_FIELDS) throw new IllegalArgumentException("Too many payload fields");
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            appendField(builder, fieldPrefix(entry.getKey()), entry.getValue());
        }
        return builder.toString();
    }

    public static Map<String, String> decode(String payload) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        Cursor cursor = cursor(payload);
        while (cursor.next()) values.put(cursor.name(), cursor.value());
        return values;
    }

    /** Legacy wire format is consecutive length-prefixed segments, including the -1:null sentinel. */
    public static String fieldPrefix(String name) {
        return name == null ? "-1:" : name.length() + ":" + name;
    }

    public static void appendField(StringBuilder target, String namePrefix, String value) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(namePrefix, "namePrefix");
        int length = value == null ? -1 : value.length();
        int digits = length < 0 ? 2 : 1;
        for (int remaining = length; remaining >= 10; remaining /= 10) digits++;
        long total = (long) target.length() + namePrefix.length() + digits + 1 + Math.max(0, length);
        if (total > MAX_PAYLOAD_CHARS) throw new IllegalArgumentException("Payload exceeds character limit");
        target.append(namePrefix).append(length).append(':');
        if (value != null) target.append(value);
    }

    public static Cursor cursor(String payload) {
        return new Cursor(payload);
    }

    /** One forward pass; no temporary maps, entries, numeric substrings or segment objects. */
    public static final class Cursor {
        private final String payload;
        private int position;
        private int fields;
        private int nameStart, nameEnd, valueStart, valueEnd;
        private boolean current;

        private Cursor(String payload) {
            this.payload = Objects.requireNonNull(payload, "payload");
            if (payload.length() > MAX_PAYLOAD_CHARS) throw new IllegalArgumentException("Payload exceeds character limit");
        }

        public boolean next() {
            current = false;
            if (position == payload.length()) return false;
            if (++fields > MAX_FIELDS) throw new IllegalArgumentException("Too many payload fields");
            int length = segmentLength();
            nameStart = length == -1 ? -1 : position;
            nameEnd = position + Math.max(0, length);
            position = nameEnd;
            length = segmentLength();
            valueStart = length == -1 ? -1 : position;
            valueEnd = position + Math.max(0, length);
            position = valueEnd;
            current = true;
            return true;
        }

        private int segmentLength() {
            int separator = payload.indexOf(':', position);
            if (separator <= position) throw new IllegalArgumentException("Malformed payload length");
            int length = Integer.parseInt(payload, position, separator, 10);
            position = separator + 1;
            if (length < -1 || length > payload.length() - position) throw new IllegalArgumentException("Malformed payload segment");
            return length;
        }

        public String name() {
            requireCurrent();
            return nameStart == -1 ? null : payload.substring(nameStart, nameEnd);
        }

        public String value() {
            requireCurrent();
            return valueStart == -1 ? null : payload.substring(valueStart, valueEnd);
        }

        private void requireCurrent() {
            if (!current) throw new IllegalStateException("Cursor is not on a field");
        }
    }
}
