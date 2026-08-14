package com.horizonradio.client.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaybackClockTest {

    @Test
    public void estimatesServerOffsetWithoutSharedSystemClocks() {
        long offset = PlaybackClock.estimateServerOffsetMs(1_000L, 6_400L, 6_500L, 1_900L);

        assertEquals(5_000L, offset);
        assertEquals(4_000L, PlaybackClock.clientTimeForServerTime(9_000L, offset));
    }

    @Test
    public void derivesFinitePlaybackPositionFromServerTimeOnAnyClientPath() {
        assertEquals(3_000L, PlaybackClock.finiteTrackPositionMs(1_000L, 10_000L, 5_000L, 7_000L));
        assertEquals(1_000L, PlaybackClock.finiteTrackPositionMs(1_000L, 0L, 5_000L, 7_000L));
    }
}
