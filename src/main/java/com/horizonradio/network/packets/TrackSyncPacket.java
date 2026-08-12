package com.horizonradio.network.packets;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Compact source-aware synchronization packet; radio has no finite-track timing fields. */
public class TrackSyncPacket implements IMessage {

    private static final int MAX_SOURCE_ID_BYTES = 128;

    private MediaSourceType sourceType;
    private String sourceId;
    private long generation;
    private long positionMs;
    private long startAtMs;
    private boolean paused;

    public TrackSyncPacket() {
        sourceType = MediaSourceType.YOUTUBE;
        sourceId = "";
    }

    public TrackSyncPacket(
        MediaSourceType sourceType, String sourceId, long generation, long positionMs, long startAtMs, boolean paused) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.generation = generation;
        this.positionMs = positionMs;
        this.startAtMs = startAtMs;
        this.paused = paused;
        validate();
    }

    /** Compatibility constructor for finite-track callers. */
    @Deprecated
    public TrackSyncPacket(long generation, String videoId, long positionMs, long startAtMs, boolean paused) {
        this(MediaSourceType.YOUTUBE, videoId, generation, positionMs, startAtMs, paused);
    }

    public static TrackSyncPacket youtube(long generation, String videoId, long positionMs, long startAtMs, boolean paused) {
        return new TrackSyncPacket(MediaSourceType.YOUTUBE, videoId, generation, positionMs, startAtMs, paused);
    }

    public static TrackSyncPacket radio(long generation, String stationUuid) {
        return new TrackSyncPacket(MediaSourceType.RADIO, stationUuid, generation, 0L, 0L, false);
    }

    public MediaSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public long getGeneration() {
        return generation;
    }

    @Deprecated
    public String getVideoId() {
        return sourceType == MediaSourceType.YOUTUBE ? sourceId : null;
    }

    public long getPositionMs() {
        return positionMs;
    }

    public long getStartAtMs() {
        return startAtMs;
    }

    public boolean isPaused() {
        return paused;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        validate();
        buf.writeByte(sourceType.getWireValue());
        PacketBufferUtil.writeString(buf, sourceId, MAX_SOURCE_ID_BYTES);
        buf.writeLong(generation);
        if (sourceType == MediaSourceType.YOUTUBE) {
            buf.writeLong(positionMs);
            buf.writeLong(startAtMs);
            buf.writeBoolean(paused);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        sourceType = MediaSourceType.fromWireValue(buf.readByte());
        sourceId = PacketBufferUtil.readString(buf, MAX_SOURCE_ID_BYTES);
        generation = buf.readLong();
        if (sourceType == MediaSourceType.YOUTUBE) {
            positionMs = buf.readLong();
            startAtMs = buf.readLong();
            paused = buf.readBoolean();
        } else {
            positionMs = 0L;
            startAtMs = 0L;
            paused = false;
        }
        validate();
    }

    private void validate() {
        if (sourceType == null || generation < 0L || sourceId == null || sourceId.trim().isEmpty()
            || sourceId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOURCE_ID_BYTES) {
            throw new IllegalArgumentException("invalid track synchronization packet");
        }
        if (sourceType == MediaSourceType.RADIO) {
            if (positionMs != 0L || startAtMs != 0L || paused) {
                throw new IllegalArgumentException("radio track synchronization cannot carry finite timing");
            }
        } else if (positionMs < 0L || startAtMs < 0L || (paused && startAtMs != 0L)) {
            throw new IllegalArgumentException("invalid finite track synchronization timing");
        }
    }
}
