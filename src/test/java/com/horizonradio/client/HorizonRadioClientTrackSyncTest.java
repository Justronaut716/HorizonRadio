package com.horizonradio.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.packets.TrackSyncPacket;

public class HorizonRadioClientTrackSyncTest {

    @Before
    public void setUp() {
        HorizonRadioClient.clearCache();
    }

    @After
    public void tearDown() {
        HorizonRadioClient.clearCache();
    }

    @Test
    public void acceptsNewerTrackGenerations() {
        assertTrue(
            HorizonRadioClient
                .shouldAcceptTrackSync(4L, "old-video", new TrackSyncPacket(5L, "new-video", 0L, 3_000L, false)));
    }

    @Test
    public void rejectsOlderOrDuplicateTrackGenerations() {
        assertFalse(
            HorizonRadioClient
                .shouldAcceptTrackSync(5L, "new-video", new TrackSyncPacket(4L, "old-video", 0L, 3_000L, false)));
        assertFalse(
            HorizonRadioClient
                .shouldAcceptTrackSync(5L, "new-video", new TrackSyncPacket(5L, "new-video", 0L, 3_000L, false)));
    }

    @Test
    public void acceptsNewerRadioGenerationUsingItsStationSourceId() {
        assertTrue(
            HorizonRadioClient.shouldAcceptTrackSync(
                4L,
                MediaSourceType.YOUTUBE,
                "old-video",
                TrackSyncPacket.radio(5L, "station-id")));
    }

    @Test
    public void handlesRadioTrackSyncAsLocalLivePlayback() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));

        assertTrue(HorizonRadioClient.isRadioActive());
        assertEquals(5L, HorizonRadioClient.getCachedRadioState().getGeneration());
        assertEquals("station-id", HorizonRadioClient.getCachedRadioState().getStationUuid());
        assertFalse(HorizonRadioClient.isPaused());
    }
}
