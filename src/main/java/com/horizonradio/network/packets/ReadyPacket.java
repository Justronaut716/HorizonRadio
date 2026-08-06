package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class ReadyPacket implements IMessage {

    private String videoId;

    public ReadyPacket() {}

    public ReadyPacket(String videoId) {
        this.videoId = videoId;
    }

    public String getVideoId() {
        return videoId;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBufferUtil.writeString(buf, videoId);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        videoId = PacketBufferUtil.readString(buf);
    }
}
