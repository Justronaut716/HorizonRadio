package com.horizonradio.network.packets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Requests that the server's cached chart results are added to the queue. */
public class AddChartsToPlaylistPacket implements IMessage {

    private static final int MAX_ENTRIES = 50;
    private List<Entry> entries = new ArrayList<Entry>();
    private boolean remove;

    public AddChartsToPlaylistPacket() {}

    public AddChartsToPlaylistPacket(List<Entry> entries) {
        this(entries, false);
    }

    public AddChartsToPlaylistPacket(List<Entry> entries, boolean remove) {
        if (entries == null || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("entries must contain at most 50 items");
        }
        this.entries = new ArrayList<Entry>(entries);
        this.remove = remove;
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public boolean isRemove() {
        return remove;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(remove);
        PacketBufferUtil.writeCount(buf, entries.size());
        for (Entry entry : entries) {
            PacketBufferUtil.writeString(buf, entry.videoId);
            PacketBufferUtil.writeString(buf, entry.title);
            PacketBufferUtil.writeString(buf, entry.duration);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        remove = buf.readBoolean();
        int count = PacketBufferUtil.readCount(buf);
        if (count > MAX_ENTRIES) {
            throw new IllegalArgumentException("too many chart entries");
        }
        entries = new ArrayList<Entry>(count);
        for (int index = 0; index < count; index++) {
            entries.add(
                new Entry(
                    PacketBufferUtil.readString(buf),
                    PacketBufferUtil.readString(buf),
                    PacketBufferUtil.readString(buf)));
        }
    }

    public static final class Entry {

        private final String videoId;
        private final String title;
        private final String duration;

        public Entry(String videoId, String title, String duration) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
        }

        public String getVideoId() {
            return videoId;
        }

        public String getTitle() {
            return title;
        }

        public String getDuration() {
            return duration;
        }
    }
}
