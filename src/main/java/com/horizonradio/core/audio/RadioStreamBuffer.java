package com.horizonradio.core.audio;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

/**
 * A small, Java-Sound-compatible PCM buffer for a single live radio generation.
 */
public final class RadioStreamBuffer {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int MAX_PACKET_BYTES = 30 * 1024;
    private static final int STARTUP_BUFFER_PACKETS = 3;
    /** Leaves room for a short packet burst while the audio line is catching up. */
    private static final int MAX_PENDING_PACKETS = 12;

    private final Queue<byte[]> pending = new ArrayDeque<byte[]>(MAX_PENDING_PACKETS);
    private long generation;
    private long highestAcceptedGeneration = Long.MIN_VALUE;
    private long nextSequence;
    private boolean active;
    private boolean ready;

    /**
     * Starts a generation only when it uses the relay's fixed PCM format.
     */
    public synchronized boolean begin(long generation, long firstSequence, int sampleRate, int channels,
        int sampleSizeInBits, boolean bigEndian) {
        if (firstSequence < 0L || sampleRate != SAMPLE_RATE
            || channels != CHANNELS
            || sampleSizeInBits != SAMPLE_SIZE_BITS
            || bigEndian) {
            return false;
        }
        if (generation <= highestAcceptedGeneration) {
            return false;
        }
        this.generation = generation;
        highestAcceptedGeneration = generation;
        this.nextSequence = firstSequence;
        this.active = true;
        this.ready = false;
        pending.clear();
        return true;
    }

    /**
     * Adds the next packet for the active generation when there is room.
     */
    public synchronized boolean accept(long generation, long sequence, byte[] data) {
        if (!active || this.generation != generation
            || sequence != nextSequence
            || data == null
            || data.length > MAX_PACKET_BYTES
            || pending.size() >= MAX_PENDING_PACKETS) {
            return false;
        }
        pending.add(Arrays.copyOf(data, data.length));
        nextSequence++;
        if (pending.size() >= STARTUP_BUFFER_PACKETS) {
            ready = true;
        }
        return true;
    }

    /**
     * Returns the next packet in order, copied so callers cannot mutate buffered state.
     */
    public synchronized byte[] poll() {
        byte[] data = pending.poll();
        return data == null ? null : Arrays.copyOf(data, data.length);
    }

    public synchronized boolean isReady() {
        return ready;
    }

    public synchronized void clear() {
        pending.clear();
        active = false;
        ready = false;
        generation = 0L;
        nextSequence = 0L;
    }

    /** Clears stream state and generation history after disconnecting from the authoritative server. */
    public synchronized void reset() {
        clear();
        highestAcceptedGeneration = Long.MIN_VALUE;
    }
}
