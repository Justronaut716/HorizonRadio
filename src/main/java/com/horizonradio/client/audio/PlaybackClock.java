package com.horizonradio.client.audio;

/** Converts server playback timestamps to the local client clock. */
public final class PlaybackClock {

    private PlaybackClock() {}

    public static long estimateServerOffsetMs(long clientSentAtMs, long serverReceivedAtMs, long serverSentAtMs,
        long clientReceivedAtMs) {
        long clientToServer = serverReceivedAtMs - clientSentAtMs;
        long serverToClient = serverSentAtMs - clientReceivedAtMs;
        return (clientToServer + serverToClient) / 2L;
    }

    public static long clientTimeForServerTime(long serverTimeMs, long serverClockOffsetMs) {
        return serverTimeMs - serverClockOffsetMs;
    }
}
