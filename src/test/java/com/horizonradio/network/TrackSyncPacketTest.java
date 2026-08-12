package com.horizonradio.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.horizonradio.network.packets.TrackSyncPacket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class TrackSyncPacketTest {

    @Test
    public void roundTripKeepsOnlyTheMinimalTrackSyncFields() {
        TrackSyncPacket original = new TrackSyncPacket(17L, "video-id", 1_250L, 9_000L, false);
        TrackSyncPacket decoded = roundTrip(original);

        assertEquals(17L, decoded.getGeneration());
        assertEquals("video-id", decoded.getVideoId());
        assertEquals(1_250L, decoded.getPositionMs());
        assertEquals(9_000L, decoded.getStartAtMs());
        assertFalse(decoded.isPaused());
    }

    @Test
    public void pausedLateJoinCarriesPositionWithoutAStartTimestamp() {
        TrackSyncPacket packet = new TrackSyncPacket(18L, "video-id", 42_000L, 0L, true);

        assertTrue(packet.isPaused());
        assertEquals(42_000L, packet.getPositionMs());
        assertEquals(0L, packet.getStartAtMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeTrackPosition() {
        new TrackSyncPacket(1L, "video-id", -1L, 2L, false);
    }

    private static TrackSyncPacket roundTrip(TrackSyncPacket original) {
        ByteBuf buffer = Unpooled.buffer();
        original.toBytes(buffer);
        TrackSyncPacket decoded = new TrackSyncPacket();
        decoded.fromBytes(buffer);
        return decoded;
    }
}
