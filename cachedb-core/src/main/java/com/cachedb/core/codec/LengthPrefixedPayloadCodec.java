package com.reactor.cachedb.core.codec;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LengthPrefixedPayloadCodec {

    private LengthPrefixedPayloadCodec() {
    }

    public static String encode(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            appendSegment(builder, entry.getKey());
            appendSegment(builder, entry.getValue());
        }
        return builder.toString();
    }

    public static Map<String, String> decode(String payload) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        int index = 0;
        while (index < payload.length()) {
            Segment keySegment = readSegment(payload, index);
            Segment valueSegment = readSegment(payload, keySegment.nextIndex());
            values.put(keySegment.value(), valueSegment.value());
            index = valueSegment.nextIndex();
        }
        return values;
    }

    private static void appendSegment(StringBuilder builder, String value) {
        if (value == null) {
            builder.append(-1).append(':');
            return;
        }
        builder.append(value.length()).append(':').append(value);
    }

    private static Segment readSegment(String payload, int startIndex) {
        int separatorIndex = payload.indexOf(':', startIndex);
        if (separatorIndex < 0) {
            throw new IllegalArgumentException("Malformed payload");
        }

        int length = Integer.parseInt(payload.substring(startIndex, separatorIndex));
        int valueStart = separatorIndex + 1;
        if (length < 0) {
            return new Segment(null, valueStart);
        }

        int valueEnd = valueStart + length;
        if (valueEnd > payload.length()) {
            throw new IllegalArgumentException("Malformed payload");
        }
        return new Segment(payload.substring(valueStart, valueEnd), valueEnd);
    }

    private record Segment(String value, int nextIndex) {
    }
}
