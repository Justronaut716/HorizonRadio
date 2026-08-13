package com.horizonradio.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.packets.PlaylistDeltaPacket;
import com.horizonradio.network.packets.PlaylistSyncPacket;
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
        assertEquals(5L, HorizonRadioClient.getCachedRadioPresentation().getGeneration());
        assertEquals("station-id", HorizonRadioClient.getCachedRadioPresentation().getStationUuid());
        assertFalse(HorizonRadioClient.isPaused());
    }

    @Test
    public void removingActiveRadioFromAuthoritativeQueueStopsLocalRadio() {
        HorizonRadioClient.handlePlaylistSnapshot(
            new PlaylistSyncPacket(
                0L,
                false,
                false,
                Arrays.asList(new PlaylistSyncPacket.Entry(MediaSourceType.RADIO, "station-id", "Alice"))));
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.radio(5L, "station-id"));

        HorizonRadioClient.handlePlaylistDelta(PlaylistDeltaPacket.remove(1L, 0));

        assertFalse(HorizonRadioClient.isRadioActive());
        assertNull(HorizonRadioClient.getCachedRadioPresentation());
    }

    @Test
    public void stopTrackSyncStopsFinitePlaybackAndRejectsTheStaleGeneration() {
        HorizonRadioClient.handleTrackSync(TrackSyncPacket.youtube(5L, "video-id", 12_000L, 0L, true));

        HorizonRadioClient.handleTrackSync(TrackSyncPacket.stop(6L));

        assertFalse(HorizonRadioClient.isPaused());
        assertFalse(
            HorizonRadioClient.shouldAcceptTrackSync(
                6L,
                null,
                null,
                TrackSyncPacket.youtube(5L, "video-id", 0L, 3_000L, false)));
        assertFalse(HorizonRadioClient.shouldAcceptTrackSync(6L, null, null, TrackSyncPacket.stop(6L)));
    }
}
