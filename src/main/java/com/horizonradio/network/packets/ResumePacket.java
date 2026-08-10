package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class ResumePacket implements IMessage {

    private long positionMs;
    private long startAtMs;

    public ResumePacket() {}

    public ResumePacket(long positionMs) {
        this(positionMs, 0L);
    }

    public ResumePacket(long positionMs, long startAtMs) {
        this.positionMs = positionMs;
        this.startAtMs = startAtMs;
    }

    public long getPositionMs() {
        return positionMs;
    }

    public long getStartAtMs() {
        return startAtMs;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(positionMs);
        buf.writeLong(startAtMs);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        positionMs = buf.readLong();
        startAtMs = buf.readLong();
    }
}
