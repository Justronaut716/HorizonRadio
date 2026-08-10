package com.horizonradio.client.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioPlayerTest {

    @Test
    public void lateResumePacketStartsAtTheSharedPlaybackPosition() {
        assertEquals(1_000L, AudioPlayer.synchronizedPositionMs(1_000L, 5_000L, 4_000L));
        assertEquals(2_000L, AudioPlayer.synchronizedPositionMs(1_000L, 5_000L, 6_000L));
    }
}
