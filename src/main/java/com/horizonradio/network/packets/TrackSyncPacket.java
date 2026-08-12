package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Minimal finite-track synchronization message. Audio bytes and display
 * metadata deliberately stay out of this packet; every client resolves the
 * video ID locally.
 */
public class TrackSyncPacket implements IMessage {

    private static final int MAX_VIDEO_ID_BYTES = 128;

    private long generation;
    private String videoId;
    private long positionMs;
    private long startAtMs;
    private boolean paused;

    public TrackSyncPacket() {
        videoId = "";
    }

    public TrackSyncPacket(long generation, String videoId, long positionMs, long startAtMs, boolean paused) {
        if (generation < 0L) {
            throw new IllegalArgumentException("track generation must not be negative");
        }
        if (videoId == null || videoId.trim()
            .length() == 0) {
            throw new IllegalArgumentException("video ID is required");
        }
        if (videoId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_VIDEO_ID_BYTES) {
            throw new IllegalArgumentException("video ID is too long");
        }
        if (positionMs < 0L || startAtMs < 0L) {
            throw new IllegalArgumentException("track timing must not be negative");
        }
        if (paused && startAtMs != 0L) {
            throw new IllegalArgumentException("paused tracks must not carry a start timestamp");
        }
        this.generation = generation;
        this.videoId = videoId;
        this.positionMs = positionMs;
        this.startAtMs = startAtMs;
        this.paused = paused;
    }

    public long getGeneration() {
        return generation;
    }

    public String getVideoId() {
        return videoId;
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
        buf.writeLong(generation);
        PacketBufferUtil.writeString(buf, videoId, MAX_VIDEO_ID_BYTES);
        buf.writeLong(positionMs);
        buf.writeLong(startAtMs);
        buf.writeBoolean(paused);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        generation = buf.readLong();
        videoId = PacketBufferUtil.readString(buf, MAX_VIDEO_ID_BYTES);
        positionMs = buf.readLong();
        startAtMs = buf.readLong();
        paused = buf.readBoolean();
        validate();
    }

    private void validate() {
        if (generation < 0L || videoId == null
            || videoId.trim()
                .length() == 0
            || positionMs < 0L
            || startAtMs < 0L
            || (paused && startAtMs != 0L)) {
            throw new IllegalArgumentException("invalid track synchronization packet");
        }
    }
}
