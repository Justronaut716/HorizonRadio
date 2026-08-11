package com.horizonradio.server.media;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Removes Shoutcast/ICY metadata blocks from a live audio response.
 *
 * <p>
 * The stream keeps only the current interval counters and one bounded
 * metadata block is discarded at a time. It therefore does not accumulate
 * station metadata or audio while a consumer is decoding.
 * </p>
 */
public final class IcyMetadataInputStream extends FilterInputStream {

    private static final int METADATA_LENGTH_UNIT = 16;

    private final int metadataInterval;
    private int audioBytesRemaining;
    private boolean closed;

    public IcyMetadataInputStream(InputStream input, int metadataInterval) {
        super(requireInput(input));
        if (metadataInterval < 0) {
            throw new IllegalArgumentException("ICY metadata interval must not be negative");
        }
        this.metadataInterval = metadataInterval;
        audioBytesRemaining = metadataInterval;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) {
            throw new NullPointerException("bytes");
        }
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("Invalid buffer range");
        }
        if (length == 0) {
            return 0;
        }
        if (closed) {
            return -1;
        }
        if (metadataInterval == 0) {
            return in.read(bytes, offset, length);
        }

        int total = 0;
        while (total < length) {
            if (audioBytesRemaining == 0) {
                if (!discardMetadataBlock()) {
                    return total == 0 ? -1 : total;
                }
            }

            int requested = Math.min(length - total, audioBytesRemaining);
            int count = in.read(bytes, offset + total, requested);
            if (count < 0) {
                return total == 0 ? -1 : total;
            }
            if (count == 0) {
                continue;
            }
            audioBytesRemaining -= count;
            total += count;
        }
        return total;
    }

    private boolean discardMetadataBlock() throws IOException {
        int length = in.read();
        if (length < 0) {
            return false;
        }
        int metadataBytes = length * METADATA_LENGTH_UNIT;
        byte[] discard = new byte[Math.min(1024, Math.max(1, metadataBytes))];
        int remaining = metadataBytes;
        while (remaining > 0) {
            int count = in.read(discard, 0, Math.min(discard.length, remaining));
            if (count < 0) {
                throw new MediaException("Truncated ICY metadata block");
            }
            if (count == 0) {
                continue;
            }
            remaining -= count;
        }
        audioBytesRemaining = metadataInterval;
        return true;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        super.close();
    }

    private static InputStream requireInput(InputStream input) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        return input;
    }
}
