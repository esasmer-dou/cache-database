package com.reactor.cachedb.core.queue;

public interface FailureClassifyingFlusher extends WriteBehindFlusher {
    WriteFailureDetails classify(Exception exception);

    static WriteFailureDetails classify(WriteBehindFlusher flusher, Exception exception) {
        if (flusher instanceof FailureClassifyingFlusher classifyingFlusher) {
            return classifyingFlusher.classify(exception);
        }
        return WriteFailureDetails.unknown(exception);
    }
}
