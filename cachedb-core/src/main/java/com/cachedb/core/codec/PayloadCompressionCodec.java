package com.reactor.cachedb.core.codec;

import com.reactor.cachedb.core.exception.CacheCodecException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

/** Version-independent wire framing with a bounded inflater and strict UTF-8. */
public final class PayloadCompressionCodec {
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final String PREFIX = "~Z:";
    private PayloadCompressionCodec() {}

    public static String compressIfBeneficial(String payload, int thresholdBytes) {
        if (payload == null) throw new CacheCodecException("Null entity payload");
        if (thresholdBytes < 0 || thresholdBytes > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Invalid compression threshold");
        if (thresholdBytes == 0) return payload;
        if (Utf8Length.of(payload) > MAX_PAYLOAD_BYTES) throw new CacheCodecException("Payload exceeds byte budget");
        byte[] input = payload.getBytes(StandardCharsets.UTF_8);
        if (input.length > MAX_PAYLOAD_BYTES) throw new CacheCodecException("Payload exceeds byte budget");
        if (input.length < thresholdBytes) return payload;
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(input); deflater.finish();
            var output = new ByteArrayOutputStream(Math.min(input.length, 8192));
            byte[] block = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(block);
                if (n == 0) throw new CacheCodecException("Compression made no progress");
                output.write(block, 0, n);
                if (3L + 4L * ((output.size() + 2L) / 3L) >= input.length) return payload;
            }
            return PREFIX + Base64.getEncoder().encodeToString(output.toByteArray());
        } finally { deflater.end(); }
    }

    public static String decompressIfNeeded(String payload, int maxBytes) {
        if (payload == null) throw new CacheCodecException("Null entity payload");
        if (maxBytes < 1 || maxBytes > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Invalid decode budget");
        if (!payload.startsWith(PREFIX)) return payload;
        if (payload.length() > 4L * ((maxBytes + 2L) / 3L) + PREFIX.length())
            throw new CacheCodecException("Compressed frame exceeds input budget");
        Inflater inflater = new Inflater();
        try {
            byte[] input = Base64.getDecoder().decode(payload.substring(PREFIX.length()));
            inflater.setInput(input);
            var output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] block = new byte[Math.min(8192, maxBytes)];
            while (!inflater.finished()) {
                int n = inflater.inflate(block);
                if (n > maxBytes - output.size()) throw new CacheCodecException("Decompressed payload exceeds byte budget");
                if (n == 0 && !inflater.finished()) throw new CacheCodecException("Truncated or unsupported compression frame");
                output.write(block, 0, n);
            }
            if (inflater.getRemaining() != 0) throw new CacheCodecException("Trailing compressed data");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (DataFormatException | IllegalArgumentException | CharacterCodingException failure) {
            throw new CacheCodecException("Invalid compressed payload", failure);
        } finally { inflater.end(); }
    }
}
