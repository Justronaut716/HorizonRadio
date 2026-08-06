package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class ResumePacket implements IMessage {

    private long positionMs;

    public ResumePacket() {}

    public ResumePacket(long positionMs) {
        this.positionMs = positionMs;
    }

    public long getPositionMs() {
        return positionMs;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(positionMs);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        positionMs = buf.readLong();
    }
}
