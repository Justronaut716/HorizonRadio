package com.horizonradio.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.packets.TrackSyncPacket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class TrackSyncPacketTest {

    @Test
    public void youtubeRoundTripKeepsFiniteTimingFields() {
        TrackSyncPacket original = TrackSyncPacket.youtube(17L, "video-id", 1_250L, 9_000L, false);
        TrackSyncPacket decoded = roundTrip(original);

        assertEquals(MediaSourceType.YOUTUBE, decoded.getSourceType());
        assertEquals(17L, decoded.getGeneration());
        assertEquals("video-id", decoded.getSourceId());
        assertEquals(1_250L, decoded.getPositionMs());
        assertEquals(9_000L, decoded.getStartAtMs());
        assertFalse(decoded.isPaused());
    }

    @Test
    public void pausedLateJoinCarriesPositionWithoutAStartTimestamp() {
        TrackSyncPacket packet = TrackSyncPacket.youtube(18L, "video-id", 42_000L, 0L, true);

        assertTrue(packet.isPaused());
        assertEquals(42_000L, packet.getPositionMs());
        assertEquals(0L, packet.getStartAtMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeTrackPosition() {
        TrackSyncPacket.youtube(1L, "video-id", -1L, 2L, false);
    }

    @Test
    public void radioTrackSyncHasNoFiniteTimingFields() {
        TrackSyncPacket packet = TrackSyncPacket.radio(4L, "station-id");
        ByteBuf buffer = Unpooled.buffer();
        packet.toBytes(buffer);
        assertEquals(20, buffer.readableBytes());
        TrackSyncPacket decoded = new TrackSyncPacket();
        decoded.fromBytes(buffer);

        assertEquals(MediaSourceType.RADIO, decoded.getSourceType());
        assertEquals("station-id", decoded.getSourceId());
        assertEquals(4L, decoded.getGeneration());
        assertEquals(0L, decoded.getPositionMs());
        assertEquals(0L, decoded.getStartAtMs());
        assertFalse(decoded.isPaused());
    }

    @Test
    public void stopTrackSyncCarriesOnlyTheNewPlaybackGeneration() {
        TrackSyncPacket packet = TrackSyncPacket.stop(19L);
        ByteBuf buffer = Unpooled.buffer();
        packet.toBytes(buffer);

        assertEquals(9, buffer.readableBytes());
        TrackSyncPacket decoded = new TrackSyncPacket();
        decoded.fromBytes(buffer);

        assertTrue(decoded.isStop());
        assertEquals(19L, decoded.getGeneration());
        assertNull(decoded.getSourceType());
        assertNull(decoded.getSourceId());
        assertEquals(0L, decoded.getPositionMs());
        assertEquals(0L, decoded.getStartAtMs());
        assertFalse(decoded.isPaused());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRadioTrackSyncWithStartTime() {
        new TrackSyncPacket(MediaSourceType.RADIO, "station-id", 4L, 0L, 1L, false);
    }

    private static TrackSyncPacket roundTrip(TrackSyncPacket original) {
        ByteBuf buffer = Unpooled.buffer();
        original.toBytes(buffer);
        TrackSyncPacket decoded = new TrackSyncPacket();
        decoded.fromBytes(buffer);
        return decoded;
    }
}
