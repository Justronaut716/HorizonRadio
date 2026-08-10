package com.horizonradio.network.packets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Informs the client that a chart-add batch has finished processing. */
public class ChartAddCompletionPacket implements IMessage {

    public static final int MAX_ENTRIES = 50;
    private static final int MAX_VIDEO_ID_BYTES = 128;

    private List<String> completedVideoIds;

    public ChartAddCompletionPacket() {
        completedVideoIds = new ArrayList<String>();
    }

    public ChartAddCompletionPacket(List<String> completedVideoIds) {
        this.completedVideoIds = copy(completedVideoIds);
    }

    public List<String> getCompletedVideoIds() {
        return Collections.unmodifiableList(completedVideoIds);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        List<String> values = copy(completedVideoIds);
        PacketBufferUtil.writeCount(buf, values.size());
        for (String videoId : values) {
            PacketBufferUtil.writeString(buf, videoId, MAX_VIDEO_ID_BYTES);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = PacketBufferUtil.readCount(buf);
        if (count > MAX_ENTRIES) {
            throw new IllegalArgumentException("chart completion must contain at most " + MAX_ENTRIES + " entries");
        }
        List<String> decoded = new ArrayList<String>(count);
        for (int index = 0; index < count; index++) {
            decoded.add(PacketBufferUtil.readString(buf, MAX_VIDEO_ID_BYTES));
        }
        completedVideoIds = decoded;
    }

    private static List<String> copy(List<String> values) {
        if (values == null || values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("chart completion must contain at most " + MAX_ENTRIES + " entries");
        }
        List<String> copy = new ArrayList<String>(values.size());
        for (String value : values) {
            if (value == null || value.length() == 0) {
                throw new IllegalArgumentException("chart completion video IDs must not be empty");
            }
            copy.add(value);
        }
        return copy;
    }
}
