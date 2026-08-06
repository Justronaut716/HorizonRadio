package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Sends a single YouTube video URL to the server for metadata import. */
public class ImportVideoPacket implements IMessage {

    private String videoUrl;

    public ImportVideoPacket() {
        videoUrl = "";
    }

    public ImportVideoPacket(String videoUrl) {
        if (videoUrl == null || videoUrl.length() > PacketBufferUtil.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("video URL is invalid");
        }
        this.videoUrl = videoUrl;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBufferUtil.writeString(buf, videoUrl);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        videoUrl = PacketBufferUtil.readString(buf);
    }
}
