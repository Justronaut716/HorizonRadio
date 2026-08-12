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
            PacketBufferUtil.writeString(buf, entry.videoId, PlaylistSyncPacket.MAX_SOURCE_ID_BYTES);
            buf.writeLong(entry.durationMs);
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
                    PacketBufferUtil.readString(buf, PlaylistSyncPacket.MAX_SOURCE_ID_BYTES),
                    buf.readLong()));
        }
    }

    public static final class Entry {

        private final String videoId;
        private final long durationMs;

        public Entry(String videoId, long durationMs) {
            if (videoId == null || videoId.trim().isEmpty() || durationMs < 0L) {
                throw new IllegalArgumentException("invalid chart playlist entry");
            }
            this.videoId = videoId;
            this.durationMs = durationMs;
        }

        /** Compatibility adapter; title is intentionally discarded. */
        @Deprecated
        public Entry(String videoId, String title, String duration) {
            this(videoId, parseDuration(duration));
        }

        public String getVideoId() {
            return videoId;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public String getDuration() {
            return formatDuration(durationMs);
        }

        @Deprecated
        public String getTitle() {
            return "";
        }

        private static long parseDuration(String duration) {
            long parsed = com.horizonradio.core.model.DurationParser.parseMillisStrict(duration);
            return parsed < 0L ? 0L : parsed;
        }

        private static String formatDuration(long durationMs) {
            long seconds = durationMs / 1000L;
            return seconds / 60L + ":" + (seconds % 60L < 10L ? "0" : "") + seconds % 60L;
        }
    }
}
