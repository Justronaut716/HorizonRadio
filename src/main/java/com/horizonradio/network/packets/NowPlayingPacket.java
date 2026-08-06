package com.horizonradio.network.packets;

import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class NowPlayingPacket implements IMessage {

    private String title;
    private float progress;

    public NowPlayingPacket() {}

    public NowPlayingPacket(String title, float progress) {
        this.title = title;
        this.progress = progress;
    }

    public String getTitle() {
        return title;
    }

    public float getProgress() {
        return progress;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBufferUtil.writeString(buf, title);
        buf.writeFloat(progress);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        title = PacketBufferUtil.readString(buf);
        progress = buf.readFloat();
    }
}
