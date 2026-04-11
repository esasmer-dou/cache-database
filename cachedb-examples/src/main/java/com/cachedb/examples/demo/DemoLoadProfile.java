package com.reactor.cachedb.examples.demo;

public record DemoLoadProfile(
        int readerThreads,
        int writerThreads,
        long readerPauseMillis,
        long writerPauseMillis
) {
    public DemoLoadProfile {
        readerThreads = Math.max(0, readerThreads);
        writerThreads = Math.max(0, writerThreads);
        readerPauseMillis = Math.max(1L, readerPauseMillis);
        writerPauseMillis = Math.max(1L, writerPauseMillis);
        if (readerThreads == 0 && writerThreads == 0) {
            throw new IllegalArgumentException("At least one reader or writer thread must be configured");
        }
    }
}
