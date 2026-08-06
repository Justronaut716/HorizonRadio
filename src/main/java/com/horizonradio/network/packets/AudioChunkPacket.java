package com.horizonradio.network.packets;

import java.util.Arrays;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class AudioChunkPacket implements IMessage {

    public static final int CHUNK_SIZE = 30 * 1024;
    public static final int MAX_CHUNKS = 4096;

    private String videoId;
    private String title;
    private int chunkIndex;
    private int totalChunks;
    private long startOffsetMs;
    private byte[] data;

    public AudioChunkPacket() {
        data = new byte[0];
    }

    public AudioChunkPacket(String videoId, String title, int chunkIndex, int totalChunks, long startOffsetMs,
        byte[] data) {
        validateChunk(chunkIndex, totalChunks);
        if (data == null || data.length > CHUNK_SIZE) {
            throw new IllegalArgumentException("audio chunk must be at most " + CHUNK_SIZE + " bytes");
        }
        this.videoId = videoId;
        this.title = title;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.startOffsetMs = startOffsetMs;
        this.data = Arrays.copyOf(data, data.length);
    }

    public String getVideoId() {
        return videoId;
    }

    public String getTitle() {
        return title;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public long getStartOffsetMs() {
        return startOffsetMs;
    }

    public byte[] getData() {
        return Arrays.copyOf(data, data.length);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        validateChunk(chunkIndex, totalChunks);
        PacketBufferUtil.writeString(buf, videoId);
        PacketBufferUtil.writeString(buf, title);
        buf.writeInt(chunkIndex);
        buf.writeInt(totalChunks);
        buf.writeLong(startOffsetMs);
        PacketBufferUtil.writeByteArray(buf, data, CHUNK_SIZE);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        videoId = PacketBufferUtil.readString(buf);
        title = PacketBufferUtil.readString(buf);
        chunkIndex = buf.readInt();
        totalChunks = buf.readInt();
        startOffsetMs = buf.readLong();
        validateChunk(chunkIndex, totalChunks);
        data = PacketBufferUtil.readByteArray(buf, CHUNK_SIZE);
    }

    private static void validateChunk(int chunkIndex, int totalChunks) {
        if (totalChunks <= 0 || totalChunks > MAX_CHUNKS || chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("invalid audio chunk index/count");
        }
    }
}
