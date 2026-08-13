package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** ID and positive finite duration only; display metadata stays client-side. */
public class AddToPlaylistPacket implements IMessage {

    private String videoId;
    private long durationMs;

    public AddToPlaylistPacket() {}

    public AddToPlaylistPacket(String videoId, long durationMs) {
        this.videoId = videoId;
        this.durationMs = durationMs;
        validate();
    }

    /** Compatibility adapter; title is intentionally discarded. */
    @Deprecated
    public AddToPlaylistPacket(String videoId, String title, String duration) {
        this(videoId, parseDuration(duration));
    }

    public String getVideoId() {
        return videoId;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Deprecated
    public String getTitle() {
        return videoId;
    }

    @Deprecated
    public String getDuration() {
        return formatDuration(durationMs);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        validate();
        PacketBufferUtil.writeString(buf, videoId, PlaylistSyncPacket.MAX_SOURCE_ID_BYTES);
        buf.writeLong(durationMs);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        videoId = PacketBufferUtil.readString(buf, PlaylistSyncPacket.MAX_SOURCE_ID_BYTES);
        durationMs = buf.readLong();
        validate();
    }

    private void validate() {
        if (videoId == null || videoId.trim().isEmpty() || durationMs <= 0L) {
            throw new IllegalArgumentException("invalid add-to-playlist packet");
        }
    }

    private static long parseDuration(String duration) {
        long parsed = com.horizonradio.core.model.DurationParser.parseMillisStrict(duration);
        return parsed < 0L ? 0L : parsed;
    }

    private static String formatDuration(long durationMs) {
        if (durationMs == 0L) {
            return "";
        }
        long seconds = durationMs / 1000L;
        return seconds / 60L + ":" + (seconds % 60L < 10L ? "0" : "") + seconds % 60L;
    }
}
