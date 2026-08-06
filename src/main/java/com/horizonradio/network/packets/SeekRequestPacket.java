package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Requests a server-authoritative seek using a normalized track position. */
public class SeekRequestPacket implements IMessage {

    private float progress;

    public SeekRequestPacket() {}

    public SeekRequestPacket(float progress) {
        this.progress = progress;
    }

    public float getProgress() {
        return progress;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeFloat(progress);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        progress = buf.readFloat();
    }
}
