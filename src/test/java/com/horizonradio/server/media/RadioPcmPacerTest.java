package com.horizonradio.server.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RadioPcmPacerTest {

    @Test
    public void normalPlaybackMaintainsThePcmSampleRate() {
        RadioPcmPacer pacer = new RadioPcmPacer();
        assertEquals(0L, pacer.reserve(17640, 0L));
        assertEquals(100000000L, pacer.reserve(17640, 50000000L));
        assertEquals(200000000L, pacer.reserve(17640, 150000000L));
    }

    @Test
    public void delayedChunksCatchUpToTheOriginalTimelineInsteadOfAddingPermanentLatency() {
        RadioPcmPacer pacer = new RadioPcmPacer();
        pacer.reserve(17640, 0L);
        assertEquals(100000000L, pacer.reserve(17640, 350000000L));
        assertEquals(200000000L, pacer.reserve(17640, 350000000L));
        assertEquals(300000000L, pacer.reserve(17640, 350000000L));
        assertEquals(400000000L, pacer.reserve(17640, 350000000L));
    }

    @Test
    public void repeatedSmallSchedulingDelaysDoNotAccumulateAcrossClients() {
        RadioPcmPacer first = new RadioPcmPacer();
        RadioPcmPacer delayed = new RadioPcmPacer();
        first.reserve(17640, 0L);
        delayed.reserve(17640, 0L);
        for (int chunk = 1; chunk < 1000; chunk++) {
            long expected = chunk * 100000000L;
            assertEquals(first.reserve(17640, expected), delayed.reserve(17640, expected + 2000000L));
        }
    }
}
