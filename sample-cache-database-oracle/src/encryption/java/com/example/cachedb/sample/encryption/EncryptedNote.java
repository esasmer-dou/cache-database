package com.example.cachedb.sample.encryption;

import com.reactor.cachedb.annotations.*;
import java.nio.charset.StandardCharsets;

@CacheEntity(table = "sample_encrypted_notes", redisNamespace = "sample-encrypted-notes", indexedColumns = {})
public record EncryptedNote(
        @CacheId(column = "note_id") String id,
        @CacheColumn("note_value")
        @CacheEncrypted(keyRing = "sample-notes", scope = EncryptionScope.REDIS_ONLY) String note) {
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("note-[1-8]");
    public EncryptedNote {
        if (id == null || !ID.matcher(id).matches() || note == null || note.isEmpty() || note.indexOf(0) >= 0 || note.length() > 512
                || !StandardCharsets.UTF_8.newEncoder().canEncode(note)
                || note.getBytes(StandardCharsets.UTF_8).length > 512)
            throw new IllegalArgumentException("Expected one of eight note IDs and 1..512 UTF-8 bytes without NUL");
    }
    @Override public String toString() { return "EncryptedNote[redacted]"; }
}
