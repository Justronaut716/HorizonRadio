package com.horizonradio.server.media;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Thread-safe bounded FIFO for normalized radio PCM chunks.
 *
 * <p>The first argument is the byte threshold required before polling starts;
 * the second is the hard maximum byte budget. Once the budget is full, the
 * oldest complete offered chunk is discarded before newer audio is retained.</p>
 */
public final class RadioJitterBuffer implements AutoCloseable {

    private final int startupThresholdBytes;
    private final int maximumBytes;
    private final Deque<byte[]> chunks = new ArrayDeque<byte[]>();
    private int bufferedBytes;
    private boolean started;
    private boolean closed;

    public RadioJitterBuffer(int startupThresholdBytes, int maximumBytes) {
        if (startupThresholdBytes <= 0 || maximumBytes <= 0 || startupThresholdBytes > maximumBytes) {
            throw new IllegalArgumentException("Invalid radio jitter-buffer bounds");
        }
        this.startupThresholdBytes = startupThresholdBytes;
        this.maximumBytes = maximumBytes;
    }

    public synchronized boolean offer(byte[] pcm) {
        if (closed || pcm == null || pcm.length == 0) {
            return false;
        }

        byte[] copy;
        if (pcm.length > maximumBytes) {
            copy = Arrays.copyOfRange(pcm, pcm.length - maximumBytes, pcm.length);
        } else {
            copy = Arrays.copyOf(pcm, pcm.length);
        }

        while (!chunks.isEmpty() && bufferedBytes + copy.length > maximumBytes) {
            bufferedBytes -= chunks.removeFirst().length;
        }
        if (bufferedBytes + copy.length > maximumBytes) {
            return false;
        }
        chunks.addLast(copy);
        bufferedBytes += copy.length;
        if (bufferedBytes >= startupThresholdBytes) {
            started = true;
        }
        return true;
    }

    public synchronized byte[] poll() {
        if (closed || chunks.isEmpty() || (!started && bufferedBytes < startupThresholdBytes)) {
            return null;
        }
        byte[] next = chunks.removeFirst();
        bufferedBytes -= next.length;
        return next;
    }

    public synchronized int getBufferedBytes() {
        return bufferedBytes;
    }

    public int getMaximumBytes() {
        return maximumBytes;
    }

    public int getStartupThresholdBytes() {
        return startupThresholdBytes;
    }

    @Override
    public synchronized void close() {
        closed = true;
        chunks.clear();
        bufferedBytes = 0;
    }
}
