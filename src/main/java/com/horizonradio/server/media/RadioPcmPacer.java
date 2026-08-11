package com.horizonradio.server.media;

/** Keeps decoded live-radio PCM at the normalized playback rate. */
final class RadioPcmPacer {

    private static final int NORMALIZED_BYTES_PER_SECOND = 44100 * 2 * 2;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private long nextDeadlineNanos;
    private boolean started;

    /** Reserves a time slot for a PCM chunk and returns its absolute start time. */
    synchronized long reserve(int bytes, long nowNanos) {
        if (bytes <= 0) {
            return nowNanos;
        }
        if (!started || nextDeadlineNanos < nowNanos) {
            nextDeadlineNanos = nowNanos;
            started = true;
        }
        long startNanos = nextDeadlineNanos;
        long durationNanos = (bytes * NANOS_PER_SECOND + NORMALIZED_BYTES_PER_SECOND - 1L)
            / NORMALIZED_BYTES_PER_SECOND;
        nextDeadlineNanos = startNanos + durationNanos;
        return startNanos;
    }
}
