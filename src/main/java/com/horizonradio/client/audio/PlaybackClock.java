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

    /** Calculates a finite track position using server timing without depending on an audio source. */
    public static long finiteTrackPositionMs(long positionMs, long serverStartAtMs, long serverClockOffsetMs,
        long clientNowMs) {
        long safePositionMs = Math.max(0L, positionMs);
        if (serverStartAtMs <= 0L) {
            return safePositionMs;
        }
        long localStartAtMs = clientTimeForServerTime(serverStartAtMs, serverClockOffsetMs);
        return safePositionMs + Math.max(0L, clientNowMs - localStartAtMs);
    }
}
