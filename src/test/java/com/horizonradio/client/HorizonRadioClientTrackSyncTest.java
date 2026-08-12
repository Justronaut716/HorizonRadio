package com.horizonradio.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.horizonradio.network.packets.TrackSyncPacket;

public class HorizonRadioClientTrackSyncTest {

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
}
