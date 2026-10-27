package com.reactor.cachedb.json;

import java.io.IOException;

/** Explicit opt-in implemented by generated codecs; custom Gson codecs retain their own writer. */
public interface GeneratedJsonWriter<V> {
    void writeValue(JsonTokenWriter writer, V value, JsonCodecLimits limits) throws IOException;
}
