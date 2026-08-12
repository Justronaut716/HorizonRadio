package com.horizonradio.network;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.packets.PlaylistDeltaPacket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PlaylistDeltaPacketTest {

    @Test
    public void playlistDeltaRoundTripsEachCompactOperation() {
        PlaylistDeltaPacket.Entry entry = new PlaylistDeltaPacket.Entry(MediaSourceType.YOUTUBE, "video-id", "Alice");
        assertAdd(roundTrip(PlaylistDeltaPacket.add(9L, entry, 2)), entry);

        PlaylistDeltaPacket remove = roundTrip(PlaylistDeltaPacket.remove(10L, 3));
        assertEquals(10L, remove.getQueueRevision());
        assertEquals(PlaylistDeltaPacket.Operation.REMOVE, remove.getOperation());
        assertEquals(3, remove.getIndex());

        PlaylistDeltaPacket move = roundTrip(PlaylistDeltaPacket.move(11L, 4, 1));
        assertEquals(11L, move.getQueueRevision());
        assertEquals(PlaylistDeltaPacket.Operation.MOVE, move.getOperation());
        assertEquals(4, move.getIndex());
        assertEquals(1, move.getTargetIndex());

        PlaylistDeltaPacket clear = roundTrip(PlaylistDeltaPacket.clear(12L));
        assertEquals(12L, clear.getQueueRevision());
        assertEquals(PlaylistDeltaPacket.Operation.CLEAR, clear.getOperation());

        PlaylistDeltaPacket replace = roundTrip(
            PlaylistDeltaPacket.replace(13L, Arrays.asList(entry, new PlaylistDeltaPacket.Entry(
                MediaSourceType.RADIO, "station-id", "Bob"))));
        assertEquals(13L, replace.getQueueRevision());
        assertEquals(PlaylistDeltaPacket.Operation.REPLACE, replace.getOperation());
        assertEquals(2, replace.getEntries().size());
        assertEquals(MediaSourceType.RADIO, replace.getEntries().get(1).getSourceType());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedReplaceBeforeListAllocation() {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeLong(1L);
        buffer.writeByte(PlaylistDeltaPacket.Operation.REPLACE.getWireValue());
        PacketBufferUtil.writeCount(buffer, PacketBufferUtil.MAX_COLLECTION_SIZE);
        new PlaylistDeltaPacket().fromBytes(buffer);
    }

    private static void assertAdd(PlaylistDeltaPacket decoded, PlaylistDeltaPacket.Entry entry) {
        assertEquals(9L, decoded.getQueueRevision());
        assertEquals(PlaylistDeltaPacket.Operation.ADD, decoded.getOperation());
        assertEquals(entry, decoded.getEntry());
        assertEquals(2, decoded.getIndex());
    }

    private static PlaylistDeltaPacket roundTrip(PlaylistDeltaPacket original) {
        ByteBuf buffer = Unpooled.buffer();
        original.toBytes(buffer);
        PlaylistDeltaPacket decoded = new PlaylistDeltaPacket();
        decoded.fromBytes(buffer);
        return decoded;
    }
}
