package com.horizonradio.network.packets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class PlaylistSyncPacket implements IMessage {

    private List<Entry> entries;

    public PlaylistSyncPacket() {
        entries = new ArrayList<Entry>();
    }

    public PlaylistSyncPacket(List<Entry> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        this.entries = new ArrayList<Entry>(entries);
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBufferUtil.writeCount(buf, entries.size());
        for (Entry entry : entries) {
            PacketBufferUtil.writeString(buf, entry.getVideoId());
            PacketBufferUtil.writeString(buf, entry.getTitle());
            PacketBufferUtil.writeString(buf, entry.getDuration());
            PacketBufferUtil.writeString(buf, entry.getAddedBy());
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = PacketBufferUtil.readCount(buf);
        List<Entry> decoded = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            decoded.add(
                new Entry(
                    PacketBufferUtil.readString(buf),
                    PacketBufferUtil.readString(buf),
                    PacketBufferUtil.readString(buf),
                    PacketBufferUtil.readString(buf)));
        }
        entries = decoded;
    }

    public static final class Entry {

        private final String videoId;
        private final String title;
        private final String duration;
        private final String addedBy;

        public Entry(String videoId, String title, String duration, String addedBy) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
            this.addedBy = addedBy;
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

        public String getAddedBy() {
            return addedBy;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry that = (Entry) other;
            return Objects.equals(videoId, that.videoId) && Objects.equals(title, that.title)
                && Objects.equals(duration, that.duration)
                && Objects.equals(addedBy, that.addedBy);
        }

        @Override
        public int hashCode() {
            return Objects.hash(videoId, title, duration, addedBy);
        }
    }
}
