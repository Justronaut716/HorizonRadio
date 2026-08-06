package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class PausePacket implements IMessage {

    private long positionMs;

    public PausePacket() {}

    public PausePacket(long positionMs) {
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
