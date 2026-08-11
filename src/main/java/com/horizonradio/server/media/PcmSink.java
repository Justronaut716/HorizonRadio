package com.horizonradio.server.media;

import java.io.Closeable;
import java.io.IOException;

/**
 * Receives normalized PCM incrementally.
 */
public interface PcmSink extends Closeable {

    void write(byte[] data, int offset, int length) throws IOException;

    /**
     * Commits a complete PCM stream. Finite-file sinks may publish their output
     * only from this method.
     */
    void finish() throws IOException;

    /**
     * Discards an incomplete PCM stream and releases its resources.
     */
    void abort() throws IOException;

    /**
     * A plain close is conservative: it aborts rather than publishing partial
     * finite media. Call {@link #finish()} to commit a successful stream.
     */
    @Override
    default void close() throws IOException {
        abort();
    }
}
