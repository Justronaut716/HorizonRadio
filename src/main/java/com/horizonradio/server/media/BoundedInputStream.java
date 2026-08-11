package com.horizonradio.server.media;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An input stream that exposes no more than a fixed number of bytes.
 */
public final class BoundedInputStream extends FilterInputStream {

    private long remaining;

    public BoundedInputStream(InputStream input, long maximumBytes) {
        super(requireInput(input));
        if (maximumBytes < 0L) {
            throw new IllegalArgumentException("Maximum byte count must not be negative");
        }
        remaining = maximumBytes;
    }

    @Override
    public int read() throws IOException {
        if (remaining == 0L) {
            return -1;
        }
        int value = in.read();
        if (value != -1) {
            remaining--;
        }
        return value;
    }

    @Override
    public int read(byte[] data, int offset, int length) throws IOException {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("Invalid buffer range");
        }
        if (length == 0) {
            return 0;
        }
        if (remaining == 0L) {
            return -1;
        }

        int allowedLength = (int) Math.min((long) length, remaining);
        int count = in.read(data, offset, allowedLength);
        if (count > 0) {
            remaining -= count;
        }
        return count;
    }

    @Override
    public long skip(long length) throws IOException {
        if (length <= 0L || remaining == 0L) {
            return 0L;
        }
        long skipped = in.skip(Math.min(length, remaining));
        if (skipped > 0L) {
            remaining -= skipped;
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min((long) in.available(), remaining);
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    public long getRemaining() {
        return remaining;
    }

    private static InputStream requireInput(InputStream input) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        return input;
    }
}
