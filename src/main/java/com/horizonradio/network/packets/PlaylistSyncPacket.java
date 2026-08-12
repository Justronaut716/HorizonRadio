package com.horizonradio.network.packets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.horizonradio.core.config.HorizonRadioConfig;
import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Compact authoritative playlist snapshot without display or stream metadata. */
public class PlaylistSyncPacket implements IMessage {

    static final int MAX_ENTRIES = HorizonRadioConfig.DEFAULT_MAX_PLAYLIST_SIZE;
    static final int MAX_SOURCE_ID_BYTES = 128;
    static final int MAX_ADDED_BY_BYTES = 64;

    private long queueRevision;
    private boolean shuffling;
    private boolean looping;
    private List<Entry> entries;

    public PlaylistSyncPacket() {
        entries = new ArrayList<Entry>();
    }

    public PlaylistSyncPacket(long queueRevision, boolean shuffling, boolean looping, List<Entry> entries) {
        validate(queueRevision, entries);
        this.queueRevision = queueRevision;
        this.shuffling = shuffling;
        this.looping = looping;
        this.entries = new ArrayList<Entry>(entries);
    }

    /** Compatibility constructor retained while callers migrate to revisioned snapshots. */
    @Deprecated
    public PlaylistSyncPacket(List<Entry> entries) {
        this(0L, false, false, entries);
    }

    public long getQueueRevision() {
        return queueRevision;
    }

    public boolean isShuffling() {
        return shuffling;
    }

    public boolean isLooping() {
        return looping;
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        validate(queueRevision, entries);
        buf.writeLong(queueRevision);
        buf.writeBoolean(shuffling);
        buf.writeBoolean(looping);
        PacketBufferUtil.writeCount(buf, entries.size());
        for (Entry entry : entries) {
            writeEntry(buf, entry);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        long decodedRevision = buf.readLong();
        boolean decodedShuffling = buf.readBoolean();
        boolean decodedLooping = buf.readBoolean();
        int count = readPlaylistCount(buf);
        List<Entry> decoded = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            decoded.add(readEntry(buf));
        }
        validate(decodedRevision, decoded);
        queueRevision = decodedRevision;
        shuffling = decodedShuffling;
        looping = decodedLooping;
        entries = decoded;
    }

    static void writeEntry(ByteBuf buf, Entry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("playlist entry must not be null");
        }
        buf.writeByte(entry.getSourceType().getWireValue());
        PacketBufferUtil.writeString(buf, entry.getSourceId(), MAX_SOURCE_ID_BYTES);
        PacketBufferUtil.writeString(buf, entry.getAddedBy(), MAX_ADDED_BY_BYTES);
    }

    static Entry readEntry(ByteBuf buf) {
        return new Entry(
            MediaSourceType.fromWireValue(buf.readByte()),
            PacketBufferUtil.readString(buf, MAX_SOURCE_ID_BYTES),
            PacketBufferUtil.readString(buf, MAX_ADDED_BY_BYTES));
    }

    static int readPlaylistCount(ByteBuf buf) {
        int count = PacketBufferUtil.readCount(buf);
        if (count > MAX_ENTRIES) {
            throw new IllegalArgumentException("playlist contains too many entries");
        }
        return count;
    }

    private static void validate(long revision, List<Entry> snapshot) {
        if (revision < 0L || snapshot == null || snapshot.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("invalid playlist snapshot");
        }
        for (Entry entry : snapshot) {
            if (entry == null) {
                throw new IllegalArgumentException("playlist entry must not be null");
            }
        }
    }

    public static final class Entry {

        private final MediaSourceType sourceType;
        private final String sourceId;
        private final String addedBy;

        public Entry(MediaSourceType sourceType, String sourceId, String addedBy) {
            if (sourceType == null || sourceId == null || sourceId.trim().isEmpty() || addedBy == null) {
                throw new IllegalArgumentException("invalid playlist packet entry");
            }
            if (sourceId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOURCE_ID_BYTES
                || addedBy.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ADDED_BY_BYTES) {
                throw new IllegalArgumentException("playlist packet entry is too long");
            }
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.addedBy = addedBy;
        }

        /** Compatibility adapter; legacy metadata is intentionally not retained or serialized. */
        @Deprecated
        public Entry(String videoId, String title, String duration, String addedBy) {
            this(MediaSourceType.YOUTUBE, videoId, addedBy);
        }

        public MediaSourceType getSourceType() {
            return sourceType;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getAddedBy() {
            return addedBy;
        }

        @Deprecated
        public String getVideoId() {
            return sourceType == MediaSourceType.YOUTUBE ? sourceId : null;
        }

        @Deprecated
        public String getTitle() {
            return sourceId;
        }

        @Deprecated
        public String getDuration() {
            return "";
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
            return sourceType == that.sourceType && Objects.equals(sourceId, that.sourceId)
                && Objects.equals(addedBy, that.addedBy);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceType, sourceId, addedBy);
        }
    }
}
